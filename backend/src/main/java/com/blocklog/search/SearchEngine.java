package com.blocklog.search;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.storage.BlockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(SearchEngine.class);

    private final BlockCatalog catalog;
    private final Semaphore scanPermits;
    private final Semaphore queryPermits;
    private final int maxActiveQueries;

    public SearchEngine(BlockCatalog catalog, int scanPermitCount, int maxActiveQueries) {
        this.catalog = catalog;
        this.scanPermits = new Semaphore(scanPermitCount);
        this.queryPermits = new Semaphore(maxActiveQueries);
        this.maxActiveQueries = maxActiveQueries;
    }

    public SearchResponse search(String tenantId, long fromMs, long toMs,
                                  Map<String, String> tags, String text,
                                  int limit, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + timeoutMs;

        if (!queryPermits.tryAcquire()) {
            return emptyResult(0, 0, 0, 0, true, false, System.currentTimeMillis() - startTime);
        }

        try {
            List<BlockMetadata> candidates = catalog.candidatesForQuery(tenantId, fromMs, toMs, tags);
            int candidateCount = candidates.size();
            int unavailableCount = catalog.unavailableCount();

            AtomicInteger scannedBlocks = new AtomicInteger();
            AtomicInteger skippedBlocks = new AtomicInteger();

            PriorityQueue<SearchResponse.SearchHit> resultHeap = new PriorityQueue<>(
                    limit + 1,
                    Comparator.comparing(SearchResponse.SearchHit::timestamp).reversed()
            );

            boolean timedOut = false;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<SearchResponse.SearchHit>>> futures = new ArrayList<>();

                for (BlockMetadata meta : candidates) {
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true;
                        break;
                    }

                    futures.add(executor.submit(() -> scanBlock(meta, fromMs, toMs, tags, text)));
                }

                for (Future<List<SearchResponse.SearchHit>> future : futures) {
                    try {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            timedOut = true;
                            break;
                        }
                        List<SearchResponse.SearchHit> hits = future.get(remaining, TimeUnit.MILLISECONDS);
                        scannedBlocks.incrementAndGet();

                        for (SearchResponse.SearchHit hit : hits) {
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
            }

            List<SearchResponse.SearchHit> sorted = new ArrayList<>(resultHeap);
            sorted.sort(Comparator.comparing(SearchResponse.SearchHit::timestamp));
            boolean truncated = resultHeap.size() >= limit && scannedBlocks.get() > 0;
            boolean partial = timedOut || skippedBlocks.get() > 0 || unavailableCount > 0;

            return new SearchResponse(
                    sorted,
                    sorted.size(),
                    partial,
                    truncated,
                    timedOut,
                    skippedBlocks.get(),
                    unavailableCount,
                    candidateCount,
                    scannedBlocks.get(),
                    System.currentTimeMillis() - startTime
            );
        } finally {
            queryPermits.release();
        }
    }

    private List<SearchResponse.SearchHit> scanBlock(BlockMetadata meta,
                                                      long fromMs, long toMs,
                                                      Map<String, String> tags, String text) throws Exception {
        scanPermits.acquire();
        try {
            BlockReader.BlockCorruptException corruptEx = null;
            List<LogRecord> records;
            try {
                var header = BlockReader.readHeader(meta.filePath());
                records = BlockReader.readRecords(meta.filePath(), header);
            } catch (BlockReader.BlockCorruptException e) {
                throw e;
            }

            List<SearchResponse.SearchHit> hits = new ArrayList<>();
            for (LogRecord record : records) {
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
            return hits;
        } finally {
            scanPermits.release();
        }
    }

    public int getActiveQueries() {
        return maxActiveQueries - queryPermits.availablePermits();
    }

    private SearchResponse emptyResult(int candidates, int scanned, int skipped, int unavailable,
                                        boolean partial, boolean timedOut, long elapsed) {
        return new SearchResponse(List.of(), 0, partial, false, timedOut,
                skipped, unavailable, candidates, scanned, elapsed);
    }
}
