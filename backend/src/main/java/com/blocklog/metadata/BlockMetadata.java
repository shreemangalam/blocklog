package com.blocklog.metadata;

import com.blocklog.storage.BlockHeader;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public record BlockMetadata(
        String blockId,
        Path filePath,
        String tenantId,
        long minTimestamp,
        long maxTimestamp,
        int recordCount,
        Map<String, Set<String>> tagSummary,
        boolean tagsSaturated,
        boolean available
) {
    public static BlockMetadata fromHeader(String blockId, Path filePath, BlockHeader header) {
        return new BlockMetadata(blockId, filePath, header.tenantId(),
                header.minTimestamp(), header.maxTimestamp(), header.recordCount(),
                header.tagSummary(), header.tagsSaturated(), true);
    }

    public static BlockMetadata unavailable(String blockId, Path filePath) {
        return new BlockMetadata(blockId, filePath, "", 0, 0, 0, Map.of(), true, false);
    }
}
