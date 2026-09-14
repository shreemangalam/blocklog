package com.blocklog.model;

import java.util.List;

public record SearchResponse(
        List<SearchHit> results,
        int returned_count,
        boolean partial,
        boolean truncated,
        boolean timed_out,
        int skipped_blocks,
        int unavailable_blocks,
        int candidate_blocks,
        int scanned_blocks,
        long elapsed_ms
) {
    public record SearchHit(
            String timestamp,
            java.util.Map<String, String> tags,
            String message
    ) {}
}
