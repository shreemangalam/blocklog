package com.blocklog.search;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void findsMatchingRecordsByTenantTimeAndText() throws Exception {
        BlockCatalog catalog = new BlockCatalog(10_000);

        List<LogRecord> records = List.of(
                new LogRecord(1000L, Map.of("env", "prod"), "payment failed for user 42"),
                new LogRecord(2000L, Map.of("env", "staging"), "payment succeeded"),
                new LogRecord(3000L, Map.of("env", "prod"), "unrelated log entry")
        );

        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-1");
        var header = BlockReader.readHeader(file);
        catalog.register(BlockMetadata.fromHeader("block-1", file, header));

        SearchEngine engine = new SearchEngine(catalog, 2, 4);
        SearchResponse response = engine.search("tenant-a", 0, 10000, Map.of("env", "prod"), "payment", 100, 5000);

        assertEquals(1, response.returned_count());
        assertFalse(response.partial());
        assertFalse(response.timed_out());
        assertEquals("payment failed for user 42", response.results().get(0).message());
    }

    @Test
    void reportsUnavailableBlocksWithoutFailingSearch() throws Exception {
        BlockCatalog catalog = new BlockCatalog(10_000);

        List<LogRecord> goodRecords = List.of(new LogRecord(1000L, Map.of(), "good message"));
        Path goodFile = BlockWriter.writeBlock(tempDir, "tenant-a", goodRecords, "good-block");
        var goodHeader = BlockReader.readHeader(goodFile);
        catalog.register(BlockMetadata.fromHeader("good-block", goodFile, goodHeader));

        Path corruptFile = tempDir.resolve("corrupt-block.blk");
        java.nio.file.Files.write(corruptFile, new byte[]{1, 2, 3});
        catalog.register(BlockMetadata.unavailable("corrupt-block", corruptFile));

        SearchEngine engine = new SearchEngine(catalog, 2, 4);
        SearchResponse response = engine.search("tenant-a", 0, 10000, null, null, 100, 5000);

        assertEquals(1, response.returned_count());
        assertTrue(response.partial());
        assertEquals(1, response.unavailable_blocks());
    }

    @Test
    void enforcesTenantIsolation() throws Exception {
        BlockCatalog catalog = new BlockCatalog(10_000);

        List<LogRecord> records = List.of(new LogRecord(1000L, Map.of(), "tenant a message"));
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-1");
        var header = BlockReader.readHeader(file);
        catalog.register(BlockMetadata.fromHeader("block-1", file, header));

        SearchEngine engine = new SearchEngine(catalog, 2, 4);
        SearchResponse response = engine.search("tenant-b", 0, 10000, null, null, 100, 5000);

        assertEquals(0, response.returned_count());
        assertEquals(0, response.candidate_blocks());
    }
}
