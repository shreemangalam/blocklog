package com.blocklog.ingest;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.EngineConfig;
import com.blocklog.model.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestionEngineTest {

    @TempDir
    Path tempDir;

    private EngineConfig testConfig() {
        return new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                5_000_000, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4
        );
    }

    @Test
    void acceptsRecordsAndTracksCounters() {
        BlockCatalog catalog = new BlockCatalog(10_000);
        IngestionEngine engine = new IngestionEngine(tempDir, testConfig(), catalog);

        List<LogRecord> records = List.of(
                new LogRecord(System.currentTimeMillis(), Map.of("env", "prod"), "test message 1"),
                new LogRecord(System.currentTimeMillis(), Map.of("env", "prod"), "test message 2")
        );

        int accepted = engine.ingest("tenant-a", records);
        assertEquals(2, accepted);
        assertEquals(2, engine.getAcceptedRecords());

        engine.shutdown();
        assertEquals(2, engine.getPersistedRecords());
        assertEquals(1, catalog.size());
    }

    @Test
    void flushesOnSizeThreshold() {
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                200, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        IngestionEngine engine = new IngestionEngine(tempDir, config, catalog);

        String bigMessage = "x".repeat(150);
        engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), bigMessage)));
        engine.ingest("tenant-a", List.of(new LogRecord(2L, Map.of(), bigMessage)));

        assertTrue(catalog.size() >= 1, "Expected at least one block flushed due to size threshold");

        engine.shutdown();
    }

    @Test
    void rejectsRecordsExceedingMaxSize() {
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 100, 16, 256, 256, 256,
                5_000_000, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        IngestionEngine engine = new IngestionEngine(tempDir, config, catalog);

        String oversized = "x".repeat(200);
        int accepted = engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), oversized)));

        assertEquals(0, accepted);
        engine.shutdown();
    }
}
