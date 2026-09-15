package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.ingest.IngestionOverloadException;
import com.blocklog.ingest.IngestionUnavailableException;
import com.blocklog.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class LogController {

    private final IngestionEngine ingestionEngine;
    private final EngineConfig config;

    public LogController(IngestionEngine ingestionEngine, EngineConfig config) {
        this.ingestionEngine = ingestionEngine;
        this.config = config;
    }

    @PostMapping("/logs")
    public ResponseEntity<?> ingestLogs(@RequestBody IngestRequest request) {
        List<String> errors = validateIngestRequest(request);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }

        List<LogRecord> records = new ArrayList<>(request.records().size());
        for (IngestRequest.RecordInput input : request.records()) {
            try {
                long timestamp = Instant.parse(input.timestamp()).toEpochMilli();
                Map<String, String> tags = input.tags() != null ? input.tags() : Map.of();
                records.add(new LogRecord(timestamp, tags, input.message()));
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid timestamp: " + input.timestamp()));
            }
        }

        try {
            int accepted = ingestionEngine.ingest(request.tenant_id(), records);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(IngestResponse.buffered(accepted));
        } catch (IngestionOverloadException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", e.getMessage()));
        } catch (IngestionUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private List<String> validateIngestRequest(IngestRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.tenant_id() == null || request.tenant_id().isBlank()) {
            errors.add("tenant_id is required");
        } else if (request.tenant_id().getBytes(StandardCharsets.UTF_8).length > config.maxTenantBytes()) {
            errors.add("tenant_id exceeds maximum length");
        }
        if (request.records() == null || request.records().isEmpty()) {
            errors.add("records must not be empty");
        } else if (request.records().size() > config.maxBatchRecords()) {
            errors.add("Too many records: max " + config.maxBatchRecords());
        }

        if (request.records() != null) {
            for (int i = 0; i < request.records().size(); i++) {
                var rec = request.records().get(i);
                if (rec.message() == null || rec.message().isBlank()) {
                    errors.add("records[" + i + "].message is required");
                }
                if (rec.timestamp() == null || rec.timestamp().isBlank()) {
                    errors.add("records[" + i + "].timestamp is required");
                }
                if (rec.tags() != null && rec.tags().size() > config.maxTagsPerRecord()) {
                    errors.add("records[" + i + "] exceeds max tags: " + config.maxTagsPerRecord());
                }
            }
        }
        return errors;
    }
}
