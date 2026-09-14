package com.blocklog.model;

public record IngestResponse(
        int accepted_records,
        String durability
) {
    public static IngestResponse buffered(int count) {
        return new IngestResponse(count, "buffered");
    }
}
