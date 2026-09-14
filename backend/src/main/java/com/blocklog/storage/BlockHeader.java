package com.blocklog.storage;

import java.util.Map;
import java.util.Set;

public record BlockHeader(
        short version,
        String tenantId,
        int recordCount,
        long minTimestamp,
        long maxTimestamp,
        int rawLength,
        int compressedLength,
        int payloadCrc32,
        Map<String, Set<String>> tagSummary,
        boolean tagsSaturated
) {
    public static final int MAGIC = 0x424C4F47; // "BLOG"
    public static final short CURRENT_VERSION = 1;
    public static final int MAX_HEADER_SIZE = 65536;
    public static final int MAX_TAG_SUMMARY_PAIRS = 64;
}
