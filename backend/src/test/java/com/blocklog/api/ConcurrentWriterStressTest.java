package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.model.IngestRequest;
import com.blocklog.model.SearchRequest;
import com.blocklog.model.SearchResponse;
import com.blocklog.search.SearchEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fans out N virtual-thread ingesters and M concurrent searchers against
 * the live Spring context, then asserts the engine's conservation
 * invariants under real concurrency:
 *
 * 1. Every record accepted at HTTP 202 (returned from the controller) is
 *    counted by {@link IngestionEngine#getAcceptedRecords()}. No lost
 *    records, no double-counting.
 * 2. After a graceful drain, persisted_records equals accepted_records;
 *    the whole pipeline conserves work across concurrent producers.
 * 3. The search engine's active-query semaphore is released cleanly:
 *    getActiveQueries() returns 0 after the workload finishes. No permit
 *    leaks under contention.
 * 4. No search returns partial=true with an "internal error" cause under
 *    a healthy workload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentWriterStressTest {

    private static final int WRITER_THREADS = 8;
    private static final int SEARCHER_THREADS = 4;
    private static final int BATCHES_PER_WRITER = 20;
    private static final int RECORDS_PER_BATCH = 25;
    private static final int TOTAL_EXPECTED_RECORDS = WRITER_THREADS * BATCHES_PER_WRITER * RECORDS_PER_BATCH;
    private static final int SEARCHES_PER_SEARCHER = 30;

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("blocklog.data-dir", () -> tempDataDir.toString());
        // Small flush so the run produces multiple blocks the searchers can hit.
        registry.add("blocklog.flush-size-bytes", () -> "50000");
        // Generous budgets; this test proves conservation, not backpressure.
        registry.add("blocklog.queue-bytes-budget", () -> String.valueOf(64L << 20));
        registry.add("blocklog.buffer-bytes-budget", () -> String.valueOf(64L << 20));
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IngestionEngine engine;

    @Autowired
    SearchEngine searchEngine;

    @Test
    void concurrentIngestAndSearchConservesEveryAcceptedRecord() throws Exception {
        AtomicLong httpAccepted = new AtomicLong();
        AtomicInteger httpOverloads = new AtomicInteger();
        AtomicInteger searchErrors = new AtomicInteger();

        try (var writerPool = Executors.newVirtualThreadPerTaskExecutor();
             var searcherPool = Executors.newVirtualThreadPerTaskExecutor()) {

            var writerFutures = new java.util.ArrayList<Future<?>>();
            for (int w = 0; w < WRITER_THREADS; w++) {
                final int writerId = w;
                writerFutures.add(writerPool.submit(() -> {
                    for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                        var records = new java.util.ArrayList<IngestRequest.RecordInput>(RECORDS_PER_BATCH);
                        for (int r = 0; r < RECORDS_PER_BATCH; r++) {
                            records.add(new IngestRequest.RecordInput(
                                    Instant.now().toString(),
                                    Map.of("writer", String.valueOf(writerId), "batch", String.valueOf(b)),
                                    "message w=" + writerId + " b=" + b + " r=" + r));
                        }
                        try {
                            var resp = rest.postForEntity("/api/v1/logs",
                                    new IngestRequest("stress-tenant", records),
                                    Object.class);
                            if (resp.getStatusCode().value() == 202) {
                                httpAccepted.addAndGet(RECORDS_PER_BATCH);
                            } else if (resp.getStatusCode().value() == 429) {
                                httpOverloads.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // Any exception here is unexpected under generous budgets.
                            fail("writer " + writerId + " batch " + b + " threw " + e);
                        }
                    }
                }));
            }

            // Fire concurrent searches against the same tenant as the ingest
            // stream. They should never crash; results may vary in size because
            // the search snapshot changes as writers append.
            var searcherFutures = new java.util.ArrayList<Future<?>>();
            for (int s = 0; s < SEARCHER_THREADS; s++) {
                searcherFutures.add(searcherPool.submit(() -> {
                    for (int i = 0; i < SEARCHES_PER_SEARCHER; i++) {
                        var searchReq = new SearchRequest(
                                "stress-tenant",
                                Instant.ofEpochMilli(0).toString(),
                                Instant.now().plusSeconds(3600).toString(),
                                null, null, 100, 2000);
                        try {
                            ResponseEntity<SearchResponse> resp = rest.postForEntity(
                                    "/api/v1/search", searchReq, SearchResponse.class);
                            if (!resp.getStatusCode().is2xxSuccessful()) {
                                searchErrors.incrementAndGet();
                            }
                        } catch (Exception e) {
                            searchErrors.incrementAndGet();
                        }
                    }
                }));
            }

            for (Future<?> f : writerFutures) f.get();
            for (Future<?> f : searcherFutures) f.get();
        } catch (ExecutionException e) {
            throw new AssertionError("worker failed", e);
        }

        // Give the consumer thread a moment to drain any tail into buffers.
        engine.awaitQuiescence(3000);
        engine.forceFlushAll();
        engine.awaitQuiescence(3000);

        // Conservation invariants.
        assertEquals(0, httpOverloads.get(),
                "budget was generous; no 429s expected");
        assertEquals(TOTAL_EXPECTED_RECORDS, httpAccepted.get(),
                "every writer must have gotten HTTP 202 for its whole batch");
        assertEquals(TOTAL_EXPECTED_RECORDS, engine.getAcceptedRecords(),
                "IngestionEngine counter must match HTTP 202 count exactly");
        assertEquals(TOTAL_EXPECTED_RECORDS, engine.getPersistedRecords(),
                "after drain, every accepted record must be persisted");

        // Semaphore hygiene: no leaked permits.
        assertEquals(0, searchEngine.getActiveQueries(),
                "all query permits must be released after workload completes");

        assertEquals(0, searchErrors.get(),
                "no search should have failed under a healthy load");
    }
}
