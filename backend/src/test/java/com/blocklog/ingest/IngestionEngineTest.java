package com.blocklog.ingest;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.EngineConfig;
import com.blocklog.model.LogRecord;
import com.blocklog.observability.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestionEngineTest {

    @TempDir
    Path tempDir;

    private IngestionEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) engine.shutdown();
    }

    private EngineConfig testConfig() {
        return new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                5_000_000, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                64L << 20, 64L << 20, 64L << 20, 5, 256
        );
    }

    private EngineMetrics newMetrics() {
        return new EngineMetrics(new SimpleMeterRegistry());
    }

    @Test
    void acceptsRecordsAndPersistsOnShutdown() throws Exception {
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, testConfig(), catalog, newMetrics(), Clock.systemUTC());

        List<LogRecord> records = List.of(
                new LogRecord(System.currentTimeMillis(), Map.of("env", "prod"), "test message 1"),
                new LogRecord(System.currentTimeMillis(), Map.of("env", "prod"), "test message 2")
        );

        int accepted = engine.ingest("tenant-a", records);
        assertEquals(2, accepted);
        assertEquals(2, engine.getAcceptedRecords());

        engine.awaitQuiescence(2000);
        engine.shutdown();
        engine = null;

        assertEquals(2, catalog.snapshot().stream().mapToInt(m -> m.recordCount()).sum());
    }

    @Test
    void flushesOnSizeThreshold() throws Exception {
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                200, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                64L << 20, 64L << 20, 64L << 20, 5, 256
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        String bigMessage = "x".repeat(150);
        engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), bigMessage)));
        engine.ingest("tenant-a", List.of(new LogRecord(2L, Map.of(), bigMessage)));

        engine.awaitQuiescence(2000);
        assertTrue(catalog.size() >= 1, "Expected at least one block from size threshold");
    }

    @Test
    void rejectsRecordsExceedingMaxSize() throws Exception {
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 100, 16, 256, 256, 256,
                5_000_000, 5, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                64L << 20, 64L << 20, 64L << 20, 5, 256
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        String oversized = "x".repeat(200);
        int accepted = engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), oversized)));

        assertEquals(0, accepted);
    }

    @Test
    void rejectsWhenQueueBudgetExhausted() throws Exception {
        // Tiny queue budget so a single record fills it.
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                5_000_000, 60, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                50L, 64L << 20, 64L << 20, 5, 256
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        assertThrows(IngestionOverloadException.class,
                () -> engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), "x".repeat(200)))));
    }
}
