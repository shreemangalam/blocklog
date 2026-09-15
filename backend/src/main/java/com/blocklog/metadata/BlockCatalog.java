package com.blocklog.metadata;

import com.blocklog.storage.BlockHeader;
import com.blocklog.storage.BlockMapping;
import com.blocklog.storage.BlockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory catalog of published blocks plus a bounded cache of read-only
 * mmap handles. Never mutates or deletes published files during a live
 * run — that means mmap handles can be reused across queries and readers
 * take independent {@code duplicate()} views.
 */
public class BlockCatalog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BlockCatalog.class);

    private final Map<String, BlockMetadata> blocks = new ConcurrentHashMap<>();
    private final Map<String, BlockMapping> mappings = new ConcurrentHashMap<>();
    private final AtomicInteger unavailableCount = new AtomicInteger();
    private final int maxBlocks;

    public BlockCatalog(int maxBlocks) {
        this.maxBlocks = maxBlocks;
    }

    public void register(BlockMetadata meta) {
        if (blocks.size() >= maxBlocks) {
            throw new IllegalStateException("Block catalog full: " + maxBlocks);
        }
        blocks.put(meta.blockId(), meta);
        if (!meta.available()) {
            unavailableCount.incrementAndGet();
        }
    }

    /**
     * Get (or open + cache) the mmap for the given block id. Returns null when
     * mapping fails; the block is then re-tagged unavailable for future queries
     * so a transient read failure does not repeatedly retry the same file.
     */
    public BlockMapping mappingFor(BlockMetadata meta) {
        BlockMapping existing = mappings.get(meta.blockId());
        if (existing != null) return existing;

        try {
            BlockMapping fresh = BlockMapping.open(meta.filePath());
            BlockMapping prior = mappings.putIfAbsent(meta.blockId(), fresh);
            if (prior != null) {
                fresh.close();
                return prior;
            }
            return fresh;
        } catch (IOException e) {
            log.warn("Failed to mmap block {}: {}", meta.blockId(), e.getMessage());
            markUnavailable(meta.blockId());
            return null;
        }
    }

    public void markUnavailable(String blockId) {
        BlockMetadata prior = blocks.get(blockId);
        if (prior == null || !prior.available()) return;
        blocks.put(blockId, BlockMetadata.unavailable(blockId, prior.filePath()));
        unavailableCount.incrementAndGet();
        BlockMapping stale = mappings.remove(blockId);
        if (stale != null) {
            try { stale.close(); } catch (IOException ignored) {}
        }
    }

    public int size() { return blocks.size(); }
    public int unavailableCount() { return unavailableCount.get(); }
    public boolean isFull() { return blocks.size() >= maxBlocks; }

    public List<BlockMetadata> snapshot() { return List.copyOf(blocks.values()); }

    /**
     * Return blocks whose tenant matches, whose [minTs, maxTs] overlaps
     * [fromMs, toMs), and (when tag summaries are not saturated) whose tag
     * union does not exclude any requested tag/value pair. Unavailable
     * blocks are dropped from candidates but reported separately on the
     * response so the client sees reduced completeness.
     */
    public PruneResult candidatesForQuery(String tenantId, long fromMs, long toMs, Map<String, String> tags) {
        List<BlockMetadata> candidates = new ArrayList<>();
        int prunedByTenant = 0, prunedByTime = 0, prunedByTag = 0;

        for (BlockMetadata meta : blocks.values()) {
            if (!meta.available()) continue;
            if (!meta.tenantId().equals(tenantId)) { prunedByTenant++; continue; }
            if (meta.maxTimestamp() < fromMs) { prunedByTime++; continue; }
            if (meta.minTimestamp() >= toMs) { prunedByTime++; continue; }

            if (tags != null && !tags.isEmpty() && !meta.tagsSaturated()) {
                boolean prune = false;
                for (var entry : tags.entrySet()) {
                    var summaryVals = meta.tagSummary().get(entry.getKey());
                    if (summaryVals != null && !summaryVals.contains(entry.getValue())) {
                        prune = true;
                        break;
                    }
                }
                if (prune) { prunedByTag++; continue; }
            }
            candidates.add(meta);
        }
        return new PruneResult(candidates, prunedByTenant + prunedByTime + prunedByTag);
    }

    public void discoverBlocks(Path dataDir) {
        if (!Files.isDirectory(dataDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.blk")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                if (filename.startsWith(".tmp.")) {
                    log.warn("Ignoring temporary file: {}", filename);
                    continue;
                }
                String blockId = filename.replace(".blk", "");
                if (blocks.containsKey(blockId)) continue;

                try {
                    BlockHeader header = BlockReader.readHeader(file);
                    register(BlockMetadata.fromHeader(blockId, file, header));
                    log.info("Recovered block: {} tenant={} records={}", blockId, header.tenantId(), header.recordCount());
                } catch (BlockReader.BlockCorruptException e) {
                    log.warn("Unavailable block {}: {}", blockId, e.getMessage());
                    register(BlockMetadata.unavailable(blockId, file));
                }
            }
        } catch (IOException e) {
            log.error("Block discovery failed", e);
        }
    }

    @Override
    public void close() {
        for (BlockMapping m : mappings.values()) {
            try { m.close(); } catch (IOException ignored) {}
        }
        mappings.clear();
    }

    public record PruneResult(List<BlockMetadata> candidates, int prunedCount) {}
}
