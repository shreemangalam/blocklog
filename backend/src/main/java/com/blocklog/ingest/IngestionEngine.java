package com.blocklog.ingest;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.EngineConfig;
import com.blocklog.model.LogRecord;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import com.blocklog.storage.RecordCodec;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingestion pipeline:
 *   HTTP thread validates + reserves bytes on the queue budget + enqueues an
 *   {@link IngestBatch} on a bounded lock-free MPSC queue. A single consumer
 *   thread drains the queue into per-tenant buffers, applies size and age
 *   flush policy, and writes blocks. Byte reservations transfer with ownership
 *   (queue budget -> buffer budget -> flush pipeline).
 */
public class IngestionEngine {

    private static final Logger log = LoggerFactory.getLogger(IngestionEngine.class);

    private final Path dataDir;
    private final EngineConfig config;
    private final BlockCatalog catalog;
    private final EngineMetrics metrics;
    private final Clock clock;

    private final TenantByteBudget queueBudget;
    private final ByteBudget bufferBudget;
    private final MpscUnboundedArrayQueue<IngestBatch> queue;
    private final Map<String, TenantBuffer> tenantBuffers = new HashMap<>();

    private final ExecutorService consumer;
    private final ScheduledExecutorService ageTimer;

    private final AtomicLong acceptedRecords = new AtomicLong();
    private final AtomicLong acceptedBytes = new AtomicLong();
    private final AtomicLong persistedRecords = new AtomicLong();
    private final AtomicLong persistedBytes = new AtomicLong();

    private volatile boolean persistenceHealthy = true;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public IngestionEngine(Path dataDir, EngineConfig config, BlockCatalog catalog,
                           EngineMetrics metrics, Clock clock) {
        this.dataDir = dataDir;
        this.config = config;
        this.catalog = catalog;
        this.metrics = metrics;
        this.clock = clock;

        // Per-tenant slice defaults to the global cap when unconfigured,
        // giving the same behavior as a single global ByteBudget.
        long perTenantQueue = config.perTenantQueueBytesBudget() > 0
                ? Math.min(config.perTenantQueueBytesBudget(), config.queueBytesBudget())
                : config.queueBytesBudget();
        this.queueBudget = new TenantByteBudget(config.queueBytesBudget(), perTenantQueue);
        this.bufferBudget = new ByteBudget(config.bufferBytesBudget());
        this.queue = new MpscUnboundedArrayQueue<>(1024);

        this.consumer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blocklog-ingest-consumer");
            t.setDaemon(true);
            return t;
        });
        consumer.submit(this::consumeLoop);

        this.ageTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blocklog-flush-tick");
            t.setDaemon(true);
            return t;
        });
        ageTimer.scheduleAtFixedRate(this::tickAgeFlush, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Called by HTTP threads. Returns the number of records accepted (equal to
     * {@code records.size()} on success). Throws {@link IngestionOverloadException}
     * when the queue or catalog budget is exhausted (map to 429), or
     * {@link IngestionUnavailableException} when persistence has failed and
     * cannot accept new work (map to 503).
     */
    public int ingest(String tenantId, List<LogRecord> records) {
        if (shutdown.get()) {
            throw new IngestionUnavailableException("Engine is shutting down");
        }
        if (!persistenceHealthy) {
            metrics.recordRejectedAdmission();
            throw new IngestionUnavailableException("Persistence unhealthy");
        }
        if (catalog.isFull()) {
            metrics.recordRejectedAdmission();
            throw new IngestionOverloadException("Block catalog full");
        }

        long batchBytes = 0;
        List<LogRecord> accepted = new ArrayList<>(records.size());
        for (LogRecord record : records) {
            int size = RecordCodec.encodedSize(record);
            if (size > config.maxRecordBytes()) continue;
            batchBytes += size;
            accepted.add(record);
        }
        if (accepted.isEmpty()) return 0;

        if (!queueBudget.tryAcquire(tenantId, batchBytes)) {
            metrics.recordRejectedAdmission();
            long tenantUse = queueBudget.tenantInUse(tenantId);
            throw new IngestionOverloadException(
                    "Ingestion queue budget exhausted (tenant=" + tenantId
                            + " tenantInUse=" + tenantUse
                            + " tenantCap=" + queueBudget.perTenantCapacity()
                            + " globalInUse=" + queueBudget.globalInUse()
                            + " globalCap=" + queueBudget.globalCapacity() + ")");
        }

        queue.offer(new IngestBatch(tenantId, accepted, batchBytes));
        metrics.recordAccepted(accepted.size(), batchBytes);
        acceptedRecords.addAndGet(accepted.size());
        acceptedBytes.addAndGet(batchBytes);
        metrics.setQueueBytes(queueBudget.globalInUse());
        return accepted.size();
    }

    private void consumeLoop() {
        while (!shutdown.get()) {
            IngestBatch batch = queue.poll();
            if (batch == null) {
                LockSupport_parkNanos(500_000);
                continue;
            }
            processBatch(batch);
        }
        drainRemaining();
    }

    private static void LockSupport_parkNanos(long nanos) {
        java.util.concurrent.locks.LockSupport.parkNanos(nanos);
    }

    private void drainRemaining() {
        IngestBatch batch;
        while ((batch = queue.poll()) != null) {
            processBatch(batch);
        }
        for (TenantBuffer b : tenantBuffers.values()) {
            if (!b.isEmpty()) flushBuffer(b, FlushReason.SHUTDOWN);
        }
    }

    private void processBatch(IngestBatch batch) {
        TenantBuffer buffer = tenantBuffers.computeIfAbsent(batch.tenantId(),
                id -> new TenantBuffer(id, config.flushSizeBytes()));

        for (LogRecord record : batch.records()) {
            int size = RecordCodec.encodedSize(record);

            if (buffer.wouldExceedSize(size)) {
                flushBuffer(buffer, FlushReason.SIZE);
            }

            // Transfer this record's bytes from the queue budget to the
            // buffer budget. If buffer budget is exhausted (unlikely under
            // normal tuning), flush and retry so we never grow past its cap.
            if (!bufferBudget.tryAcquire(size)) {
                flushBuffer(buffer, FlushReason.SIZE);
                bufferBudget.tryAcquire(size); // guaranteed after flush drains the tenant
            }

            buffer.add(record, size, clock.millis());
        }

        // Bytes are now owned by tenant buffers (and any blocks we flushed
        // above). Release the queue-budget slice for the whole batch.
        queueBudget.release(batch.tenantId(), batch.reservedBytes());
        metrics.setQueueBytes(queueBudget.globalInUse());
        metrics.setBufferBytes(bufferBudget.inUse());
    }

    private void tickAgeFlush() {
        try {
            long now = clock.millis();
            long thresholdMs = config.flushAgeSeconds() * 1000L;
            for (TenantBuffer b : tenantBuffers.values()) {
                if (!b.isEmpty() && (now - b.oldestRecordEpochMs()) >= thresholdMs) {
                    flushBuffer(b, FlushReason.AGE);
                }
            }
        } catch (Throwable t) {
            log.error("Age-flush tick failed", t);
        }
    }

    private void flushBuffer(TenantBuffer buffer, FlushReason reason) {
        if (buffer.isEmpty()) return;

        long flushStart = System.nanoTime();
        int records = buffer.size();
        long bufferedBytes = buffer.currentSize();
        List<LogRecord> drained = buffer.drain();
        String blockId = UUID.randomUUID().toString();
        String tenantId = buffer.tenantId();

        try {
            Files.createDirectories(dataDir);
            Path file = BlockWriter.writeBlock(dataDir, tenantId, drained, blockId);
            var header = BlockReader.readHeader(file);
            catalog.register(BlockMetadata.fromHeader(blockId, file, header));

            persistedRecords.addAndGet(records);
            persistedBytes.addAndGet(bufferedBytes);
            metrics.recordPersisted(records, bufferedBytes);
            metrics.setCatalogUnavailable(catalog.unavailableCount());
            switch (reason) {
                case SIZE, SHUTDOWN -> metrics.recordFlushBySize();
                case AGE -> metrics.recordFlushByAge();
            }
            metrics.recordFlushDuration(System.nanoTime() - flushStart);

            log.info("Flushed block {} tenant={} records={} reason={}",
                    blockId, tenantId, records, reason);
        } catch (Exception e) {
            log.error("Flush failed for tenant {}", tenantId, e);
            metrics.recordFlushFailure();
            persistenceHealthy = false;
        } finally {
            bufferBudget.release(bufferedBytes);
            metrics.setBufferBytes(bufferBudget.inUse());
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;

        ageTimer.shutdown();
        consumer.shutdown();
        try {
            if (!consumer.awaitTermination(config.shutdownDeadlineSeconds(), TimeUnit.SECONDS)) {
                log.warn("Consumer did not drain within deadline; forcing stop");
                consumer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumer.shutdownNow();
        }
    }

    public long getAcceptedRecords() { return acceptedRecords.get(); }
    public long getAcceptedBytes() { return acceptedBytes.get(); }
    public long getPersistedRecords() { return persistedRecords.get(); }
    public long getPersistedBytes() { return persistedBytes.get(); }
    public boolean isPersistenceHealthy() { return persistenceHealthy; }
    public double getBufferUsagePct() { return bufferBudget.usagePct(); }
    public double getQueueUsagePct() { return queueBudget.globalUsagePct(); }

    /** Test hook: block until the queue is empty and any in-flight batch is done. */
    public void awaitQuiescence(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && queueBudget.globalInUse() == 0) return;
            Thread.sleep(10);
        }
    }

    /** Test hook: force flush of all non-empty buffers. */
    public void forceFlushAll() {
        for (TenantBuffer b : tenantBuffers.values()) {
            if (!b.isEmpty()) flushBuffer(b, FlushReason.SIZE);
        }
    }

    private enum FlushReason { SIZE, AGE, SHUTDOWN }

    private static final class TenantBuffer {
        private final String tenantId;
        private final int flushSizeBytes;
        private final List<LogRecord> records = new ArrayList<>();
        private int currentSize = 0;
        private long oldestRecordEpochMs = 0;

        TenantBuffer(String tenantId, int flushSizeBytes) {
            this.tenantId = tenantId;
            this.flushSizeBytes = flushSizeBytes;
        }

        String tenantId() { return tenantId; }
        boolean isEmpty() { return records.isEmpty(); }
        int size() { return records.size(); }
        int currentSize() { return currentSize; }
        long oldestRecordEpochMs() { return oldestRecordEpochMs; }

        boolean wouldExceedSize(int additionalBytes) {
            return !records.isEmpty() && currentSize + additionalBytes > flushSizeBytes;
        }

        void add(LogRecord record, int size, long epochMs) {
            if (records.isEmpty()) {
                oldestRecordEpochMs = epochMs;
            }
            records.add(record);
            currentSize += size;
        }

        List<LogRecord> drain() {
            List<LogRecord> drained = new ArrayList<>(records);
            records.clear();
            currentSize = 0;
            oldestRecordEpochMs = 0;
            return drained;
        }
    }
}
