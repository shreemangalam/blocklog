package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.model.IngestRequest;
import com.blocklog.model.IngestResponse;
import com.blocklog.model.SearchRequest;
import com.blocklog.model.SearchResponse;
import com.blocklog.model.StatusResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full HTTP surface integration test: ingest -> flush -> search round-trip,
 * /status counters, and the typed 429 backpressure mapping introduced when
 * IngestionOverloadException replaced string-matched IllegalStateException.
 *
 * The queue byte budget is intentionally small so an oversized single
 * record trips the queue-budget guard without touching the per-record
 * validation limit; small records still fit for the happy-path cases.
 * A tiny flush-size guarantees a size-flush from a single record so the
 * search test doesn't need to wait for the 5s age tick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiIntegrationTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("blocklog.data-dir", () -> tempDataDir.toString());
        registry.add("blocklog.flush-size-bytes", () -> "80");
        registry.add("blocklog.queue-bytes-budget", () -> "512");
        registry.add("blocklog.buffer-bytes-budget", () -> "1048576");
        registry.add("blocklog.result-bytes-budget", () -> "1048576");
        registry.add("blocklog.max-record-bytes", () -> "65536");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IngestionEngine engine;

    @Test
    @Order(1)
    void ingestFlushSearchRoundTrip() throws Exception {
        String ts = Instant.ofEpochMilli(1_700_000_000_000L).toString();
        IngestRequest req = new IngestRequest("tenant-a", List.of(
                new IngestRequest.RecordInput(ts, Map.of("env", "prod"), "payment failed for user 42")
        ));

        ResponseEntity<IngestResponse> ingestResp = rest.postForEntity(
                "/api/v1/logs", req, IngestResponse.class);
        assertEquals(HttpStatus.ACCEPTED, ingestResp.getStatusCode());
        assertNotNull(ingestResp.getBody());
        assertEquals(1, ingestResp.getBody().accepted_records());
        assertEquals("buffered", ingestResp.getBody().durability());

        // Second record forces the buffer past flush-size-bytes.
        String ts2 = Instant.ofEpochMilli(1_700_000_000_500L).toString();
        rest.postForEntity("/api/v1/logs",
                new IngestRequest("tenant-a", List.of(
                        new IngestRequest.RecordInput(ts2, Map.of("env", "prod"),
                                "payment retried for user 42"))),
                IngestResponse.class);

        engine.awaitQuiescence(2000);
        engine.forceFlushAll();
        engine.awaitQuiescence(2000);

        SearchRequest search = new SearchRequest(
                "tenant-a",
                Instant.ofEpochMilli(1_699_000_000_000L).toString(),
                Instant.ofEpochMilli(1_800_000_000_000L).toString(),
                Map.of("env", "prod"),
                "payment",
                100,
                5000
        );
        ResponseEntity<SearchResponse> searchResp = rest.postForEntity(
                "/api/v1/search", search, SearchResponse.class);
        assertEquals(HttpStatus.OK, searchResp.getStatusCode());
        SearchResponse body = searchResp.getBody();
        assertNotNull(body);
        assertEquals(2, body.returned_count(), "both matching records should be returned");
        assertFalse(body.timed_out());
        assertTrue(body.candidate_blocks() >= 1);
    }

    @Test
    @Order(2)
    void statusEndpointReflectsCounters() {
        ResponseEntity<StatusResponse> resp = rest.getForEntity("/api/v1/status", StatusResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        StatusResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals("healthy", body.status());
        assertTrue(body.accepted_records() >= 2);
        assertTrue(body.persisted_records() >= 2);
        assertTrue(body.published_blocks() >= 1);
        assertTrue(body.persistence_healthy());
        assertTrue(body.buffer_usage_pct() >= 0.0);
    }

    @Test
    @Order(3)
    void oversizedBatchReturns429WhenQueueBudgetExhausted() {
        // Single record whose encoded size exceeds the 512-byte queue budget
        // but stays under max-record-bytes; must trip queueBudget.tryAcquire.
        String bigMessage = "x".repeat(2000);
        String ts = Instant.ofEpochMilli(1_700_000_100_000L).toString();

        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/v1/logs",
                new IngestRequest("tenant-a", List.of(
                        new IngestRequest.RecordInput(ts, Map.of(), bigMessage))),
                Map.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().get("error").toString().toLowerCase().contains("queue"),
                "expected queue-budget message, got: " + resp.getBody());
    }

    @Test
    @Order(4)
    void badRequestOnMissingTenant() {
        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/v1/logs",
                new IngestRequest("", List.of(
                        new IngestRequest.RecordInput(
                                Instant.now().toString(), Map.of(), "no tenant"))),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @Order(5)
    void badRequestOnInvalidTimestamp() {
        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/v1/logs",
                new IngestRequest("tenant-a", List.of(
                        new IngestRequest.RecordInput("not-a-time", Map.of(), "bad ts"))),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @Order(6)
    void searchWithWrongTenantReturnsEmpty() {
        SearchRequest search = new SearchRequest(
                "other-tenant",
                Instant.ofEpochMilli(0).toString(),
                Instant.now().plusSeconds(3600).toString(),
                null, null, 100, 5000
        );
        ResponseEntity<SearchResponse> resp = rest.postForEntity(
                "/api/v1/search", search, SearchResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().returned_count());
        assertEquals(0, resp.getBody().candidate_blocks());
    }

}
