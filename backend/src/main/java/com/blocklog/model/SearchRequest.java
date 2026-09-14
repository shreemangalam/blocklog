package com.blocklog.model;

import java.util.Map;

public record SearchRequest(
        String tenant_id,
        String from,
        String to,
        Map<String, String> tags,
        String text,
        Integer limit,
        Integer timeout_ms
) {}
