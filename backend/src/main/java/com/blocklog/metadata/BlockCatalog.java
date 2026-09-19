package com.blocklog.metadata;

import com.blocklog.storage.BlockHeader;
import com.blocklog.storage.BlockMapping;
import com.blocklog.storage.BlockReader;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
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
 * mmap handles.
 *
 * The mapping cache is Caffeine-backed, bounded by {@code mmapCacheSize},
 * with a removal listener that closes the evicted {@link BlockMapping}'s
 * FileChannel. Concurrent scanners hold {@code duplicate()} views into the
 * MappedByteBuffer; the mapping lifetime is tied to buffer garbage
 * collection, so closing the channel on eviction does not invalidate a
 * duplicate that is still being read. Published block files are never
 * mutated or deleted during a live run.
 *
 * The cache is a soft ceiling; Caffeine may briefly hold slightly more
 * than {@code mmapCacheSize} while background eviction catches up. Callers
 * must not depend on an exact upper bound.
 */
public class BlockCatalog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BlockCatalog.class);

    /** Default cache size when the caller doesn't specify one. */
    public static final int DEFAULT_MMAP_CACHE_SIZE = 256;

    private final Map<String, BlockMetadata> blocks = new ConcurrentHashMap<>();
    private final Cache<String, BlockMapping> mappings;
    private final AtomicInteger unavailableCount = new AtomicInteger();
    private final int maxBlocks;

    public BlockCatalog(int maxBlocks) {
        this(maxBlocks, DEFAULT_MMAP_CACHE_SIZE);
    }

    public BlockCatalog(int maxBlocks, int mmapCacheSize) {
        if (mmapCacheSize < 1) throw new IllegalArgumentException("mmapCacheSize must be >= 1");
        this.maxBlocks = maxBlocks;
        this.mappings = Caffeine.newBuilder()
                .maximumSize(mmapCacheSize)
                .removalListener((String blockId, BlockMapping mapping, RemovalCause cause) -> {
                    if (mapping == null) return;
                    try {
                        mapping.close();
                    } catch (IOException e) {
                        log.warn("Failed to close evicted mmap {} (cause={}): {}", blockId, cause, e.getMessage());
                    }
                })
                .build();
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
     *
     * Uses Caffeine's atomic {@code get(k, loader)} so concurrent callers on the
     * same missing block open at most one mapping; the removal listener closes
     * one if the load races.
     */
    public BlockMapping mappingFor(BlockMetadata meta) {
        return mappings.get(meta.blockId(), id -> {
            try {
                return BlockMapping.open(meta.filePath());
            } catch (IOException e) {
                log.warn("Failed to mmap block {}: {}", id, e.getMessage());
                markUnavailable(id);
                return null;
            }
        });
    }

    /** Test hook: how many mmap handles the cache currently retains. */
    public long cachedMappingCount() {
        mappings.cleanUp();
        return mappings.estimatedSize();
    }

    public void markUnavailable(String blockId) {
        BlockMetadata prior = blocks.get(blockId);
        if (prior == null || !prior.available()) return;
        blocks.put(blockId, BlockMetadata.unavailable(blockId, prior.filePath()));
        unavailableCount.incrementAndGet();
        // Invalidate triggers the removal listener which closes the mapping.
        mappings.invalidate(blockId);
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
        // Invalidate every entry; the removal listener closes each mapping.
        mappings.invalidateAll();
        mappings.cleanUp();
    }

    public record PruneResult(List<BlockMetadata> candidates, int prunedCount) {}
}
