package com.blocklog.model;

public record StatusResponse(
        String status,
        long accepted_records,
        long persisted_records,
        long accepted_bytes,
        long persisted_bytes,
        int published_blocks,
        int unavailable_blocks,
        int active_queries,
        double buffer_usage_pct,
        boolean persistence_healthy
) {}
