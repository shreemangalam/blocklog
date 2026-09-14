package com.blocklog.metadata;

import com.blocklog.storage.BlockHeader;
import com.blocklog.storage.BlockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockCatalog {

    private static final Logger log = LoggerFactory.getLogger(BlockCatalog.class);

    private final Map<String, BlockMetadata> blocks = new ConcurrentHashMap<>();
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

    public int size() {
        return blocks.size();
    }

    public int unavailableCount() {
        return unavailableCount.get();
    }

    public boolean isFull() {
        return blocks.size() >= maxBlocks;
    }

    public List<BlockMetadata> snapshot() {
        return List.copyOf(blocks.values());
    }

    public List<BlockMetadata> candidatesForQuery(String tenantId, long fromMs, long toMs, Map<String, String> tags) {
        List<BlockMetadata> candidates = new ArrayList<>();
        for (BlockMetadata meta : blocks.values()) {
            if (!meta.available()) continue;
            if (!meta.tenantId().equals(tenantId)) continue;
            if (meta.maxTimestamp() < fromMs) continue;
            if (meta.minTimestamp() >= toMs) continue;

            if (tags != null && !tags.isEmpty() && !meta.tagsSaturated()) {
                boolean pruned = false;
                for (var entry : tags.entrySet()) {
                    var summaryVals = meta.tagSummary().get(entry.getKey());
                    if (summaryVals != null && !summaryVals.contains(entry.getValue())) {
                        pruned = true;
                        break;
                    }
                }
                if (pruned) continue;
            }

            candidates.add(meta);
        }
        return candidates;
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
}
