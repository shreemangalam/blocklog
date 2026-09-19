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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded virtual-thread search executor.
 *
 * - Global active-query semaphore rejects excess queries with an explicit
 *   partial response (rather than blocking indefinitely).
 * - Global scan-permit semaphore bounds concurrent per-block decompression.
 * - Deadline is checked between blocks AND periodically inside a scan.
 * - Truncation is flagged when totalHitsSeen across all blocks exceeds the
 *   final result set size.
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

    private record ScoredHit(long timestampMs, String blockId, int recordIndex,
                              SearchResponse.SearchHit hit) {}

    private static final Comparator<ScoredHit> SCORED_HIT_ASC = Comparator
            .comparingLong(ScoredHit::timestampMs)
            .thenComparing(ScoredHit::blockId)
            .thenComparingInt(ScoredHit::recordIndex);

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

            PriorityQueue<ScoredHit> resultHeap = new PriorityQueue<>(
                    Math.max(limit, 1) + 1,
                    SCORED_HIT_ASC.reversed()
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
                        for (ScoredHit scored : outcome.hits()) {
                            resultHeap.offer(scored);
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

            List<ScoredHit> sorted = new ArrayList<>(resultHeap);
            sorted.sort(SCORED_HIT_ASC);

            List<SearchResponse.SearchHit> results = new ArrayList<>(sorted.size());
            for (ScoredHit s : sorted) results.add(s.hit());

            boolean truncated = totalHitsSeen.get() > results.size();
            boolean partial = timedOut
                    || skippedBlocks.get() > 0
                    || unavailableAtStart > 0
                    || scannedBlocks.get() < candidateCount;

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSearch(elapsedNanos, candidateCount, scannedBlocks.get(),
                    prunedCount, skippedBlocks.get(), timedOut);

            return new SearchResponse(
                    results,
                    results.size(),
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
            String blockId = meta.blockId().toString();
            List<ScoredHit> hits = new ArrayList<>();
            AtomicBoolean deadlineReached = new AtomicBoolean();

            BlockReader.forEachRecord(mapping, header, (record, recordIndex) -> {
                if ((recordIndex & 0xFF) == 0 && recordIndex > 0 && System.nanoTime() >= deadlineNanos) {
                    deadlineReached.set(true);
                    return false;
                }
                if (Thread.currentThread().isInterrupted()) {
                    deadlineReached.set(true);
                    return false;
                }
                if (record.timestamp() < fromMs || record.timestamp() >= toMs) return true;

                if (tags != null && !tags.isEmpty()) {
                    for (var entry : tags.entrySet()) {
                        if (!entry.getValue().equals(record.tags().get(entry.getKey()))) {
                            return true;
                        }
                    }
                }

                if (text != null && !text.isEmpty() && !record.message().contains(text)) {
                    return true;
                }

                hits.add(new ScoredHit(
                        record.timestamp(),
                        blockId,
                        recordIndex,
                        new SearchResponse.SearchHit(
                                Instant.ofEpochMilli(record.timestamp()).toString(),
                                record.tags(),
                                record.message()
                        )
                ));
                return true;
            });

            return new ScanOutcome(hits, false, deadlineReached.get());
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

    private record ScanOutcome(List<ScoredHit> hits, boolean failed, boolean timedOut) {
        static ScanOutcome asFailed() { return new ScanOutcome(List.of(), true, false); }
        static ScanOutcome asTimedOut() { return new ScanOutcome(List.of(), false, true); }
    }
}
