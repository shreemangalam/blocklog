package com.blocklog.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blocklog")
public record EngineConfig(
        String dataDir,
        int maxBatchRecords,
        int maxBatchBytes,
        int maxRecordBytes,
        int maxTagsPerRecord,
        int maxTagKeyBytes,
        int maxTagValueBytes,
        int maxTenantBytes,
        int flushSizeBytes,
        int flushAgeSeconds,
        int maxBlocks,
        int maxQueryResults,
        int defaultQueryResults,
        int maxQueryTimeoutMs,
        int defaultQueryTimeoutMs,
        int scanPermits,
        int maxActiveQueries,
        long queueBytesBudget,
        long bufferBytesBudget,
        int shutdownDeadlineSeconds,
        int mmapCacheSize,
        long perTenantQueueBytesBudget
) {
    public int effectiveScanPermits() {
        return scanPermits > 0 ? scanPermits : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
}
