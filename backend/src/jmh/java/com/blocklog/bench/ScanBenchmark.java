package com.blocklog.bench;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.search.SearchEngine;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end scan latency against a fixed on-disk block set. Each trial
 * writes {@code blockCount} blocks of {@code recordsPerBlock} records and
 * measures full-tenant scans through the real SearchEngine (mmap catalog +
 * virtual-thread scan + heap merge).
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
public class ScanBenchmark {

    @Param({"8", "32"})
    public int blockCount;

    @Param({"250", "1000"})
    public int recordsPerBlock;

    private Path dataDir;
    private BlockCatalog catalog;
    private SearchEngine engine;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dataDir = Files.createTempDirectory("blocklog-bench-");
        catalog = new BlockCatalog(blockCount + 8);

        long ts = 1_700_000_000_000L;
        for (int b = 0; b < blockCount; b++) {
            List<LogRecord> records = new ArrayList<>(recordsPerBlock);
            for (int i = 0; i < recordsPerBlock; i++) {
                records.add(new LogRecord(
                        ts++,
                        Map.of("env", (i & 3) == 0 ? "prod" : "stage",
                                "svc", "svc-" + (i & 7)),
                        "request " + i + " completed in " + (i * 3) + "ms"));
            }
            String blockId = UUID.randomUUID().toString();
            Path file = BlockWriter.writeBlock(dataDir, "tenant-a", records, blockId);
            var header = BlockReader.readHeader(file);
            catalog.register(BlockMetadata.fromHeader(blockId, file, header));
        }

        engine = new SearchEngine(catalog,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                4,
                new EngineMetrics(new SimpleMeterRegistry()));
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        catalog.close();
        try (var stream = Files.walk(dataDir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Benchmark
    public void scanFullRangeText(Blackhole bh) {
        SearchResponse resp = engine.search(
                "tenant-a", 0L, Long.MAX_VALUE, null, "completed", 100, 30_000);
        bh.consume(resp);
    }

    @Benchmark
    public void scanTagFilteredText(Blackhole bh) {
        SearchResponse resp = engine.search(
                "tenant-a", 0L, Long.MAX_VALUE, Map.of("env", "prod"),
                "request", 100, 30_000);
        bh.consume(resp);
    }
}
