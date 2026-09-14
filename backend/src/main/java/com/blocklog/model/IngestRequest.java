package com.blocklog.model;

import java.util.List;
import java.util.Map;

public record IngestRequest(
        String tenant_id,
        List<RecordInput> records
) {
    public record RecordInput(
            String timestamp,
            Map<String, String> tags,
            String message
    ) {}
}
