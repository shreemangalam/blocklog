package com.blocklog.search;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.storage.BlockMapping;
import com.blocklog.storage.BlockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded virtual-thread search executor.
 *
 * - Global active-query semaphore rejects excess queries with an explicit
 *   partial response (rather than blocking indefinitely).
 * - Global scan-permit semaphore bounds concurrent per-block decompression.
 * - Deadline is checked between blocks AND periodically inside a scan.
 * - Truncation is flagged when any block generated more matches than the
 *   result heap could keep (not the buggy heap.size() >= limit heuristic).
 * - Cancels outstanding scans on timeout so we don't leak threads or
 *   scan-permit reservations.
 */
public class SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(SearchEngine.class);

    private final BlockCatalog catalog;
    private final Semaphore scanPermits;
    private final Semaphore queryPermits;
    private final int maxActiveQueries;
    private final EngineMetrics metrics;

    public SearchEngine(BlockCatalog catalog, int scanPermitCount, int maxActiveQueries, EngineMetrics metrics) {
        this.catalog = catalog;
        this.scanPermits = new Semaphore(scanPermitCount);
        this.queryPermits = new Semaphore(maxActiveQueries);
        this.maxActiveQueries = maxActiveQueries;
        this.metrics = metrics;
        metrics.setScanPermitsFree(scanPermits.availablePermits());
    }

    public SearchResponse search(String tenantId, long fromMs, long toMs,
                                  Map<String, String> tags, String text,
                                  int limit, long timeoutMs) {
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        if (!queryPermits.tryAcquire()) {
            metrics.recordQueryRejectedActive();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new SearchResponse(List.of(), 0, true, false, false,
                    0, catalog.unavailableCount(), 0, 0, elapsed);
        }
        metrics.setActiveQueries(maxActiveQueries - queryPermits.availablePermits());

        try {
            BlockCatalog.PruneResult prune = catalog.candidatesForQuery(tenantId, fromMs, toMs, tags);
            List<BlockMetadata> candidates = prune.candidates();
            int candidateCount = candidates.size();
            int prunedCount = prune.prunedCount();
            int unavailableAtStart = catalog.unavailableCount();

            AtomicInteger scannedBlocks = new AtomicInteger();
            AtomicInteger skippedBlocks = new AtomicInteger();
            AtomicLong totalHitsSeen = new AtomicLong();

            PriorityQueue<SearchResponse.SearchHit> resultHeap = new PriorityQueue<>(
                    Math.max(limit, 1) + 1,
                    Comparator.<SearchResponse.SearchHit>comparingLong(
                            h -> Instant.parse(h.timestamp()).toEpochMilli()).reversed()
            );

            boolean timedOut = false;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<ScanOutcome>> futures = new ArrayList<>(candidates.size());
                for (BlockMetadata meta : candidates) {
                    futures.add(executor.submit(() ->
                            scanBlock(meta, fromMs, toMs, tags, text, deadlineNanos)));
                }

                for (Future<ScanOutcome> future : futures) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        timedOut = true;
                        break;
                    }
                    try {
                        ScanOutcome outcome = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                        if (outcome.failed()) {
                            skippedBlocks.incrementAndGet();
                            continue;
                        }
                        if (outcome.timedOut()) {
                            timedOut = true;
                        }
                        scannedBlocks.incrementAndGet();
                        totalHitsSeen.addAndGet(outcome.hits().size());
                        for (SearchResponse.SearchHit hit : outcome.hits()) {
                            resultHeap.offer(hit);
                            if (resultHeap.size() > limit) {
                                resultHeap.poll();
                            }
                        }
                    } catch (TimeoutException e) {
                        timedOut = true;
                        break;
                    } catch (ExecutionException e) {
                        skippedBlocks.incrementAndGet();
                        log.warn("Block scan failed", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        timedOut = true;
                        break;
                    }
                }

                if (timedOut) {
                    for (Future<ScanOutcome> f : futures) f.cancel(true);
                }
            }

            List<SearchResponse.SearchHit> sorted = new ArrayList<>(resultHeap);
            sorted.sort(Comparator.comparingLong(h -> Instant.parse(h.timestamp()).toEpochMilli()));

            boolean truncated = totalHitsSeen.get() > sorted.size();
            boolean partial = timedOut
                    || skippedBlocks.get() > 0
                    || unavailableAtStart > 0
                    || scannedBlocks.get() < candidateCount;

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSearch(elapsedNanos, candidateCount, scannedBlocks.get(),
                    prunedCount, skippedBlocks.get(), timedOut);

            return new SearchResponse(
                    sorted,
                    sorted.size(),
                    partial,
                    truncated,
                    timedOut,
                    skippedBlocks.get(),
                    unavailableAtStart,
                    candidateCount,
                    scannedBlocks.get(),
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
            );
        } finally {
            queryPermits.release();
            metrics.setActiveQueries(maxActiveQueries - queryPermits.availablePermits());
        }
    }

    private ScanOutcome scanBlock(BlockMetadata meta,
                                   long fromMs, long toMs,
                                   Map<String, String> tags, String text,
                                   long deadlineNanos) {
        long permitWaitNanos = deadlineNanos - System.nanoTime();
        if (permitWaitNanos <= 0) return ScanOutcome.asTimedOut();

        boolean acquired;
        try {
            acquired = scanPermits.tryAcquire(permitWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScanOutcome.asTimedOut();
        }
        if (!acquired) return ScanOutcome.asTimedOut();
        metrics.setScanPermitsFree(scanPermits.availablePermits());

        try {
            if (System.nanoTime() >= deadlineNanos) return ScanOutcome.asTimedOut();

            BlockMapping mapping = catalog.mappingFor(meta);
            if (mapping == null) return ScanOutcome.asFailed();

            var header = BlockReader.parseHeaderFromMapping(mapping);
            List<LogRecord> records = BlockReader.readRecords(mapping, header);

            List<SearchResponse.SearchHit> hits = new ArrayList<>();
            int checked = 0;
            for (LogRecord record : records) {
                if ((++checked & 0xFF) == 0 && System.nanoTime() >= deadlineNanos) {
                    return new ScanOutcome(hits, false, true);
                }
                if (Thread.currentThread().isInterrupted()) {
                    return new ScanOutcome(hits, false, true);
                }
                if (record.timestamp() < fromMs || record.timestamp() >= toMs) continue;

                if (tags != null && !tags.isEmpty()) {
                    boolean allMatch = true;
                    for (var entry : tags.entrySet()) {
                        if (!entry.getValue().equals(record.tags().get(entry.getKey()))) {
                            allMatch = false;
                            break;
                        }
                    }
                    if (!allMatch) continue;
                }

                if (text != null && !text.isEmpty() && !record.message().contains(text)) {
                    continue;
                }

                hits.add(new SearchResponse.SearchHit(
                        Instant.ofEpochMilli(record.timestamp()).toString(),
                        record.tags(),
                        record.message()
                ));
            }
            return new ScanOutcome(hits, false, false);
        } catch (BlockReader.BlockCorruptException e) {
            log.warn("Corrupt block {} during scan: {}", meta.blockId(), e.getMessage());
            catalog.markUnavailable(meta.blockId());
            return ScanOutcome.asFailed();
        } catch (Exception e) {
            log.warn("Unexpected error scanning block {}", meta.blockId(), e);
            return ScanOutcome.asFailed();
        } finally {
            scanPermits.release();
            metrics.setScanPermitsFree(scanPermits.availablePermits());
        }
    }

    public int getActiveQueries() {
        return maxActiveQueries - queryPermits.availablePermits();
    }

    private record ScanOutcome(List<SearchResponse.SearchHit> hits, boolean failed, boolean timedOut) {
        static ScanOutcome asFailed() { return new ScanOutcome(List.of(), true, false); }
        static ScanOutcome asTimedOut() { return new ScanOutcome(List.of(), false, true); }
    }
}
