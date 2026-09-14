package com.blocklog.model;

import java.util.Map;

public record LogRecord(
        long timestamp,
        Map<String, String> tags,
        String message
) {
    public LogRecord {
        if (tags == null) tags = Map.of();
        if (message == null) throw new IllegalArgumentException("message must not be null");
    }
}
