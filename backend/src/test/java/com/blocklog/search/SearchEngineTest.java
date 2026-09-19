package com.blocklog.search;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTest {

    @TempDir
    Path tempDir;

    private BlockCatalog catalog;

    @AfterEach
    void tearDown() {
        if (catalog != null) catalog.close();
    }

    private EngineMetrics newMetrics() {
        return new EngineMetrics(new SimpleMeterRegistry());
    }

    @Test
    void findsMatchingRecordsByTenantTimeAndText() throws Exception {
        catalog = new BlockCatalog(10_000);

        List<LogRecord> records = List.of(
                new LogRecord(1000L, Map.of("env", "prod"), "payment failed for user 42"),
                new LogRecord(2000L, Map.of("env", "staging"), "payment succeeded"),
                new LogRecord(3000L, Map.of("env", "prod"), "unrelated log entry")
        );

        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-1");
        var header = BlockReader.readHeader(file);
        catalog.register(BlockMetadata.fromHeader("block-1", file, header));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());
        SearchResponse response = engine.search("tenant-a", 0, 10000, Map.of("env", "prod"), "payment", 100, 5000);

        assertEquals(1, response.returned_count());
        assertFalse(response.partial());
        assertFalse(response.timed_out());
        assertEquals("payment failed for user 42", response.results().get(0).message());
    }

    @Test
    void reportsUnavailableBlocksWithoutFailingSearch() throws Exception {
        catalog = new BlockCatalog(10_000);

        List<LogRecord> goodRecords = List.of(new LogRecord(1000L, Map.of(), "good message"));
        Path goodFile = BlockWriter.writeBlock(tempDir, "tenant-a", goodRecords, "good-block");
        var goodHeader = BlockReader.readHeader(goodFile);
        catalog.register(BlockMetadata.fromHeader("good-block", goodFile, goodHeader));

        Path corruptFile = tempDir.resolve("corrupt-block.blk");
        Files.write(corruptFile, new byte[]{1, 2, 3});
        catalog.register(BlockMetadata.unavailable("corrupt-block", corruptFile));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());
        SearchResponse response = engine.search("tenant-a", 0, 10000, null, null, 100, 5000);

        assertEquals(1, response.returned_count());
        assertTrue(response.partial());
        assertEquals(1, response.unavailable_blocks());
    }

    @Test
    void enforcesTenantIsolation() throws Exception {
        catalog = new BlockCatalog(10_000);

        List<LogRecord> records = List.of(new LogRecord(1000L, Map.of(), "tenant a message"));
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-1");
        var header = BlockReader.readHeader(file);
        catalog.register(BlockMetadata.fromHeader("block-1", file, header));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());
        SearchResponse response = engine.search("tenant-b", 0, 10000, null, null, 100, 5000);

        assertEquals(0, response.returned_count());
        assertEquals(0, response.candidate_blocks());
    }

    @Test
    void truncationFlaggedWhenMoreMatchesThanLimit() throws Exception {
        catalog = new BlockCatalog(10_000);
        java.util.ArrayList<LogRecord> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new LogRecord(i * 100L, Map.of("env", "prod"), "message " + i));
        }
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", many, "block-many");
        var header = BlockReader.readHeader(file);
        catalog.register(BlockMetadata.fromHeader("block-many", file, header));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());
        SearchResponse response = engine.search("tenant-a", 0, 1_000_000, null, null, 5, 5000);

        assertEquals(5, response.returned_count());
        assertTrue(response.truncated(), "Truncated flag should be set when more matches than limit");
    }

    @Test
    void queryReturnsTimedOutInsteadOfBlockingUnderScanPermitStarvation() throws Exception {
        catalog = new BlockCatalog(10_000);
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a",
                List.of(new LogRecord(1000L, Map.of(), "hello world")),
                "block-1");
        catalog.register(BlockMetadata.fromHeader("block-1", file, BlockReader.readHeader(file)));

        // scanPermits = 0 -> every scan permit acquire must fail after its deadline.
        // Without the deadline-bounded tryAcquire this call would block indefinitely.
        SearchEngine engine = new SearchEngine(catalog, 0, 4, newMetrics());

        long timeoutMs = 300;
        long startNanos = System.nanoTime();
        SearchResponse response = engine.search(
                "tenant-a", 0, 10_000, null, null, 100, timeoutMs);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(response.timed_out(), "search must flag timed_out when no scan permit is ever available");
        assertTrue(response.partial(), "response must be partial when timed_out");
        assertEquals(0, response.returned_count());
        assertTrue(elapsedMs < timeoutMs + 500,
                "search must return near the deadline, not block; took " + elapsedMs + "ms");
    }

    @Test
    void earliestKOrderingCorrectWhenTimestampHasZeroMillis() throws Exception {
        // Instant.toString() omits fractional seconds when millis == 0.
        // "1970-01-01T00:00:01Z" vs "1970-01-01T00:00:01.001Z": '.' (46) < 'Z' (90)
        // so the LATER record sorts lexicographically before the EARLIER one.
        // The heap comparator must use epoch-ms, not string comparison.
        catalog = new BlockCatalog(10_000);
        List<LogRecord> records = List.of(
                new LogRecord(1000L, Map.of(), "earlier"),  // millis==0 -> "T00:00:01Z"
                new LogRecord(1001L, Map.of(), "later")     // millis==1 -> "T00:00:01.001Z"
        );
        Path file = BlockWriter.writeBlock(tempDir, "t1", records, "ord-block");
        catalog.register(BlockMetadata.fromHeader("ord-block", file, BlockReader.readHeader(file)));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());

        SearchResponse limitOne = engine.search("t1", 0, 10_000, null, null, 1, 5000);
        assertEquals(1, limitOne.returned_count());
        assertEquals("earlier", limitOne.results().get(0).message(),
                "limit=1 must return the earliest record, not the lexicographically smallest timestamp");

        SearchResponse both = engine.search("t1", 0, 10_000, null, null, 10, 5000);
        assertEquals(2, both.returned_count());
        assertEquals("earlier", both.results().get(0).message(), "ascending order: earlier first");
        assertEquals("later", both.results().get(1).message(), "ascending order: later second");
    }

    @Test
    void tenantAndTimePruningAreExact() throws Exception {
        catalog = new BlockCatalog(10_000);

        // Two blocks, one entirely before the query window.
        Path oldFile = BlockWriter.writeBlock(tempDir, "tenant-a",
                List.of(new LogRecord(100L, Map.of(), "old")), "block-old");
        catalog.register(BlockMetadata.fromHeader("block-old", oldFile, BlockReader.readHeader(oldFile)));

        Path newFile = BlockWriter.writeBlock(tempDir, "tenant-a",
                List.of(new LogRecord(5000L, Map.of(), "new")), "block-new");
        catalog.register(BlockMetadata.fromHeader("block-new", newFile, BlockReader.readHeader(newFile)));

        SearchEngine engine = new SearchEngine(catalog, 2, 4, newMetrics());
        SearchResponse response = engine.search("tenant-a", 1000, 10_000, null, null, 100, 5000);

        assertEquals(1, response.returned_count());
        assertEquals(1, response.candidate_blocks());
        assertEquals("new", response.results().get(0).message());
    }
}
