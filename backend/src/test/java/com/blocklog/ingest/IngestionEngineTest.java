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
                64L << 20, 64L << 20, 5, 256, 0L
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
                64L << 20, 64L << 20, 5, 256, 0L
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
                64L << 20, 64L << 20, 5, 256, 0L
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        String oversized = "x".repeat(200);
        assertThrows(RecordTooLargeException.class, () ->
                engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), oversized))));
    }

    @Test
    void rejectsWhenQueueBudgetExhausted() throws Exception {
        // Tiny queue budget so a single record fills it.
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                5_000_000, 60, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                50L, 64L << 20, 5, 256, 0L
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        assertThrows(IngestionOverloadException.class,
                () -> engine.ingest("tenant-a", List.of(new LogRecord(1L, Map.of(), "x".repeat(200)))));
    }

    @Test
    void perTenantBudgetPreventsOneTenantFromStarvingOthers() throws Exception {
        // Global queue: 4 KiB. Per-tenant slice: 300 bytes.
        // A tenant that sends a batch whose whole-batch bytes exceed 300 is
        // rejected on admission, even though the global 4 KiB has plenty of
        // room. Other tenants must still be able to ingest.
        EngineConfig config = new EngineConfig(
                tempDir.toString(), 1000, 5_000_000, 65536, 16, 256, 256, 256,
                5_000_000, 60, 10_000, 1000, 100, 30_000, 5_000, 1, 4,
                4096L, 64L << 20, 5, 256, 300L
        );
        BlockCatalog catalog = new BlockCatalog(10_000);
        engine = new IngestionEngine(tempDir, config, catalog, newMetrics(), Clock.systemUTC());

        // A single record whose encoded size exceeds the 300-byte tenant slice.
        // 400-byte body + framing/tag overhead sits comfortably above 300 and
        // well under the 64 KiB max-record-bytes.
        var big = new LogRecord(1L, Map.of(), "x".repeat(400));

        IngestionOverloadException ex = assertThrows(IngestionOverloadException.class,
                () -> engine.ingest("tenant-noisy", List.of(big)));
        assertTrue(ex.getMessage().contains("tenant=tenant-noisy"),
                "message should identify the tenant that hit its cap: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tenantCap=300"),
                "message should include the tenant cap: " + ex.getMessage());

        // A different tenant has a fresh 300-byte slice and small records fit.
        int accepted = engine.ingest("tenant-quiet",
                List.of(new LogRecord(3L, Map.of(), "z".repeat(80))));
        assertEquals(1, accepted, "per-tenant cap must not affect other tenants");
    }
}
