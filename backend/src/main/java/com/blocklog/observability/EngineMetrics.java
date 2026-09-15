package com.blocklog.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-cardinality counters, gauges, and timers for the engine.
 *
 * All labels avoid tenant IDs, tag values, and message content to keep the
 * metric surface bounded. Rates and pressure are what oncall watches; sample
 * detail lives in logs.
 */
@Component
public class EngineMetrics {

    private final Counter acceptedRecords;
    private final Counter acceptedBytes;
    private final Counter rejectedAdmission;
    private final Counter persistedRecords;
    private final Counter persistedBytes;
    private final Counter flushBySize;
    private final Counter flushByAge;
    private final Counter flushFailures;
    private final Counter publishedBlocks;
    private final Counter candidateBlocks;
    private final Counter scannedBlocks;
    private final Counter prunedBlocks;
    private final Counter skippedBlocks;
    private final Counter queriesTimedOut;
    private final Counter queriesRejectedActive;
    private final Timer flushLatency;
    private final Timer searchLatency;

    private final AtomicLong queueBytes = new AtomicLong();
    private final AtomicLong bufferBytes = new AtomicLong();
    private final AtomicLong activeQueries = new AtomicLong();
    private final AtomicLong scanPermitsFree = new AtomicLong();
    private final AtomicLong catalogUnavailable = new AtomicLong();

    public EngineMetrics(MeterRegistry registry) {
        this.acceptedRecords = Counter.builder("blocklog.ingest.accepted.records")
                .description("Records accepted into the ingestion queue")
                .register(registry);
        this.acceptedBytes = Counter.builder("blocklog.ingest.accepted.bytes")
                .description("Encoded bytes accepted into the ingestion queue")
                .baseUnit("bytes").register(registry);
        this.rejectedAdmission = Counter.builder("blocklog.ingest.rejected")
                .description("Ingestion batches rejected at admission (budget or catalog full)")
                .register(registry);
        this.persistedRecords = Counter.builder("blocklog.persist.records")
                .description("Records durably persisted to published blocks")
                .register(registry);
        this.persistedBytes = Counter.builder("blocklog.persist.bytes")
                .description("Compressed bytes durably persisted")
                .baseUnit("bytes").register(registry);
        this.flushBySize = Counter.builder("blocklog.flush.by").tag("reason", "size").register(registry);
        this.flushByAge = Counter.builder("blocklog.flush.by").tag("reason", "age").register(registry);
        this.flushFailures = Counter.builder("blocklog.flush.failures").register(registry);
        this.publishedBlocks = Counter.builder("blocklog.blocks.published").register(registry);
        this.candidateBlocks = Counter.builder("blocklog.search.blocks.candidates").register(registry);
        this.scannedBlocks = Counter.builder("blocklog.search.blocks.scanned").register(registry);
        this.prunedBlocks = Counter.builder("blocklog.search.blocks.pruned").register(registry);
        this.skippedBlocks = Counter.builder("blocklog.search.blocks.skipped").register(registry);
        this.queriesTimedOut = Counter.builder("blocklog.search.queries.timed_out").register(registry);
        this.queriesRejectedActive = Counter.builder("blocklog.search.queries.rejected")
                .tag("reason", "active_limit").register(registry);
        this.flushLatency = Timer.builder("blocklog.flush.duration").register(registry);
        this.searchLatency = Timer.builder("blocklog.search.duration").register(registry);

        registry.gauge("blocklog.queue.bytes", queueBytes);
        registry.gauge("blocklog.buffer.bytes", bufferBytes);
        registry.gauge("blocklog.search.queries.active", activeQueries);
        registry.gauge("blocklog.search.scan_permits.free", scanPermitsFree);
        registry.gauge("blocklog.catalog.unavailable", catalogUnavailable);
    }

    public void recordAccepted(int records, long bytes) {
        acceptedRecords.increment(records);
        acceptedBytes.increment(bytes);
    }

    public void recordRejectedAdmission() { rejectedAdmission.increment(); }

    public void recordPersisted(int records, long bytes) {
        persistedRecords.increment(records);
        persistedBytes.increment(bytes);
        publishedBlocks.increment();
    }

    public void recordFlushBySize() { flushBySize.increment(); }
    public void recordFlushByAge() { flushByAge.increment(); }
    public void recordFlushFailure() { flushFailures.increment(); }

    public void recordFlushDuration(long nanos) {
        flushLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordSearch(long nanos, int candidates, int scanned, int pruned,
                              int skipped, boolean timedOut) {
        searchLatency.record(nanos, TimeUnit.NANOSECONDS);
        candidateBlocks.increment(candidates);
        scannedBlocks.increment(scanned);
        prunedBlocks.increment(pruned);
        skippedBlocks.increment(skipped);
        if (timedOut) queriesTimedOut.increment();
    }

    public void recordQueryRejectedActive() { queriesRejectedActive.increment(); }

    public void setQueueBytes(long bytes) { queueBytes.set(bytes); }
    public void setBufferBytes(long bytes) { bufferBytes.set(bytes); }
    public void setActiveQueries(long count) { activeQueries.set(count); }
    public void setScanPermitsFree(long count) { scanPermitsFree.set(count); }
    public void setCatalogUnavailable(long count) { catalogUnavailable.set(count); }
}
