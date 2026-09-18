package com.blocklog.api;

import com.blocklog.model.IngestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the IngestionUnavailableException -> HTTP 503 mapping end-to-end.
 *
 * Trick: point blocklog.data-dir at a file that already exists as a regular
 * file (not a directory). The first flush attempt calls
 * {@code Files.createDirectories(dataDir)}, which throws
 * FileAlreadyExistsException. The catch block in IngestionEngine.flushBuffer
 * flips persistenceHealthy to false. Any subsequent ingest is refused with
 * IngestionUnavailableException -> HTTP 503.
 *
 * We drive the flush deterministically by shrinking flushSize to a value
 * a single record will exceed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionUnhealthyIntegrationTest {

    @TempDir
    static Path scratch;

    /** A regular file at this path defeats mkdirs and forces flush failure. */
    static Path badDataPath;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) throws IOException {
        badDataPath = scratch.resolve("not-a-directory");
        Files.writeString(badDataPath, "this is a file, not a data dir");

        registry.add("blocklog.data-dir", () -> badDataPath.toString());
        // Small flush-size so a single record trips the size-flush path.
        registry.add("blocklog.flush-size-bytes", () -> "40");
        registry.add("blocklog.buffer-bytes-budget", () -> "1048576");
        registry.add("blocklog.queue-bytes-budget", () -> "1048576");
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void ingestReturns503AfterPersistenceFailure() throws Exception {
        String ts = Instant.now().toString();

        // First ingest: goes onto the queue with HTTP 202, but the flush that
        // follows will fail because data-dir cannot be created. That flip
        // races with our second call, so poll for it briefly.
        ResponseEntity<?> first = rest.postForEntity("/api/v1/logs",
                new IngestRequest("tenant-x", List.of(
                        new IngestRequest.RecordInput(ts, Map.of("k", "v"),
                                "trigger a flush attempt with a long-enough message to exceed flush-size"))),
                Object.class);
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode(),
                "first record must be buffered before persistence has failed");

        // Wait for the flush to fail and flip persistenceHealthy. 1s cap is
        // plenty on a healthy runner; the flush pipeline reacts in ms.
        HttpStatus status = pollUntilPersistenceDegraded(1000);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, status,
                "second ingest must return 503 once the engine marks persistence unhealthy");
    }

    private HttpStatus pollUntilPersistenceDegraded(long deadlineMs) throws InterruptedException {
        long stop = System.currentTimeMillis() + deadlineMs;
        HttpStatus last = HttpStatus.ACCEPTED;
        while (System.currentTimeMillis() < stop) {
            ResponseEntity<?> resp = rest.postForEntity("/api/v1/logs",
                    new IngestRequest("tenant-x", List.of(
                            new IngestRequest.RecordInput(Instant.now().toString(),
                                    Map.of(), "keep trying until 503"))),
                    Object.class);
            last = HttpStatus.valueOf(resp.getStatusCode().value());
            if (last == HttpStatus.SERVICE_UNAVAILABLE) return last;
            Thread.sleep(20);
        }
        return last;
    }
}
