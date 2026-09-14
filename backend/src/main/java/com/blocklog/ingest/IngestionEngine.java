package com.blocklog.ingest;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.EngineConfig;
import com.blocklog.model.LogRecord;
import com.blocklog.storage.BlockWriter;
import com.blocklog.storage.RecordCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class IngestionEngine {

    private static final Logger log = LoggerFactory.getLogger(IngestionEngine.class);

    private final Path dataDir;
    private final EngineConfig config;
    private final BlockCatalog catalog;
    private final Map<String, TenantBuffer> tenantBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flushScheduler;
    private final AtomicLong acceptedRecords = new AtomicLong();
    private final AtomicLong acceptedBytes = new AtomicLong();
    private final AtomicLong persistedRecords = new AtomicLong();
    private final AtomicLong persistedBytes = new AtomicLong();
    private volatile boolean persistenceHealthy = true;
    private volatile boolean shutdown = false;

    public IngestionEngine(Path dataDir, EngineConfig config, BlockCatalog catalog) {
        this.dataDir = dataDir;
        this.config = config;
        this.catalog = catalog;

        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blocklog-flush");
            t.setDaemon(true);
            return t;
        });

        flushScheduler.scheduleAtFixedRate(this::flushAgedBuffers, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized int ingest(String tenantId, List<LogRecord> records) {
        if (shutdown) {
            throw new IllegalStateException("Engine is shut down");
        }
        if (!persistenceHealthy) {
            throw new IllegalStateException("Persistence unhealthy");
        }
        if (catalog.isFull()) {
            throw new IllegalStateException("Block catalog full");
        }

        TenantBuffer buffer = tenantBuffers.computeIfAbsent(tenantId,
                id -> new TenantBuffer(id, config.flushSizeBytes()));

        int accepted = 0;
        for (LogRecord record : records) {
            int recordSize = RecordCodec.encodedSize(record);
            if (recordSize > config.maxRecordBytes()) {
                continue;
            }

            if (buffer.wouldExceedSize(recordSize)) {
                flushBuffer(buffer);
            }

            buffer.add(record, recordSize);
            accepted++;
            acceptedRecords.incrementAndGet();
            acceptedBytes.addAndGet(recordSize);
        }

        return accepted;
    }

    private void flushBuffer(TenantBuffer buffer) {
        if (buffer.isEmpty()) return;

        List<LogRecord> records = buffer.drain();
        String blockId = UUID.randomUUID().toString();
        String tenantId = buffer.tenantId();

        try {
            Files.createDirectories(dataDir);
            Path file = BlockWriter.writeBlock(dataDir, tenantId, records, blockId);
            var header = com.blocklog.storage.BlockReader.readHeader(file);
            catalog.register(BlockMetadata.fromHeader(blockId, file, header));

            int count = records.size();
            long bytes = records.stream().mapToLong(RecordCodec::encodedSize).sum();
            persistedRecords.addAndGet(count);
            persistedBytes.addAndGet(bytes);

            log.info("Flushed block {} for tenant {} ({} records)", blockId, tenantId, count);
        } catch (Exception e) {
            log.error("Flush failed for tenant {}", tenantId, e);
            persistenceHealthy = false;
        }
    }

    private void flushAgedBuffers() {
        long now = System.nanoTime();
        long ageThresholdNanos = config.flushAgeSeconds() * 1_000_000_000L;

        for (TenantBuffer buffer : tenantBuffers.values()) {
            if (!buffer.isEmpty() && (now - buffer.oldestRecordNanos()) >= ageThresholdNanos) {
                flushBuffer(buffer);
            }
        }
    }

    public void shutdown() {
        shutdown = true;
        flushScheduler.shutdown();
        for (TenantBuffer buffer : tenantBuffers.values()) {
            flushBuffer(buffer);
        }
    }

    public long getAcceptedRecords() { return acceptedRecords.get(); }
    public long getAcceptedBytes() { return acceptedBytes.get(); }
    public long getPersistedRecords() { return persistedRecords.get(); }
    public long getPersistedBytes() { return persistedBytes.get(); }
    public boolean isPersistenceHealthy() { return persistenceHealthy; }

    public double getBufferUsagePct() {
        long buffered = tenantBuffers.values().stream().mapToLong(TenantBuffer::currentSize).sum();
        long capacity = (long) config.flushSizeBytes() * Math.max(1, tenantBuffers.size());
        return capacity > 0 ? (buffered * 100.0 / capacity) : 0.0;
    }

    private static class TenantBuffer {
        private final String tenantId;
        private final int flushSizeBytes;
        private final List<LogRecord> records = new ArrayList<>();
        private int currentSize = 0;
        private long oldestRecordNanos = 0;

        TenantBuffer(String tenantId, int flushSizeBytes) {
            this.tenantId = tenantId;
            this.flushSizeBytes = flushSizeBytes;
        }

        String tenantId() { return tenantId; }

        boolean isEmpty() { return records.isEmpty(); }

        int currentSize() { return currentSize; }

        long oldestRecordNanos() { return oldestRecordNanos; }

        boolean wouldExceedSize(int additionalBytes) {
            return currentSize + additionalBytes > flushSizeBytes;
        }

        void add(LogRecord record, int size) {
            if (records.isEmpty()) {
                oldestRecordNanos = System.nanoTime();
            }
            records.add(record);
            currentSize += size;
        }

        List<LogRecord> drain() {
            List<LogRecord> drained = new ArrayList<>(records);
            records.clear();
            currentSize = 0;
            oldestRecordNanos = 0;
            return drained;
        }
    }
}
