package com.blocklog.bench;

import com.blocklog.model.LogRecord;
import com.blocklog.storage.RecordCodec;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CodecBenchmark {

    @Param({"64", "512", "2048"})
    public int messageBytes;

    @Param({"0", "4"})
    public int tagCount;

    private LogRecord record;
    private byte[] encoded;

    @Setup(Level.Trial)
    public void setup() {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < tagCount; i++) {
            tags.put("tag" + i, "value" + i);
        }
        String message = "x".repeat(messageBytes);
        record = new LogRecord(1_700_000_000_000L, tags, message);
        encoded = RecordCodec.encode(record);
    }

    @Benchmark
    public void encode(Blackhole bh) {
        bh.consume(RecordCodec.encode(record));
    }

    @Benchmark
    public void decode(Blackhole bh) {
        bh.consume(RecordCodec.decode(ByteBuffer.wrap(encoded)));
    }

    @Benchmark
    public void roundTrip(Blackhole bh) {
        byte[] bytes = RecordCodec.encode(record);
        bh.consume(RecordCodec.decode(ByteBuffer.wrap(bytes)));
    }
}
