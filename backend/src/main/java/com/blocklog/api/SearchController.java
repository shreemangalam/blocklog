package com.blocklog.api;

import com.blocklog.model.EngineConfig;
import com.blocklog.model.SearchRequest;
import com.blocklog.model.SearchResponse;
import com.blocklog.search.SearchEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchEngine searchEngine;
    private final EngineConfig config;

    public SearchController(SearchEngine searchEngine, EngineConfig config) {
        this.searchEngine = searchEngine;
        this.config = config;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest request) {
        List<String> errors = validateSearchRequest(request);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }

        long fromMs, toMs;
        try {
            fromMs = Instant.parse(request.from()).toEpochMilli();
            toMs = Instant.parse(request.to()).toEpochMilli();
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid timestamp format"));
        }

        int limit = request.limit() != null ? request.limit() : config.defaultQueryResults();
        limit = Math.min(limit, config.maxQueryResults());

        int timeoutMs = request.timeout_ms() != null ? request.timeout_ms() : config.defaultQueryTimeoutMs();
        timeoutMs = Math.min(timeoutMs, config.maxQueryTimeoutMs());

        SearchResponse response = searchEngine.search(
                request.tenant_id(), fromMs, toMs,
                request.tags(), request.text(),
                limit, timeoutMs
        );

        return ResponseEntity.ok(response);
    }

    private List<String> validateSearchRequest(SearchRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.tenant_id() == null || request.tenant_id().isBlank()) {
            errors.add("tenant_id is required");
        }
        if (request.from() == null || request.from().isBlank()) {
            errors.add("from is required");
        }
        if (request.to() == null || request.to().isBlank()) {
            errors.add("to is required");
        }

        if (request.from() != null && request.to() != null &&
                !request.from().isBlank() && !request.to().isBlank()) {
            try {
                Instant from = Instant.parse(request.from());
                Instant to = Instant.parse(request.to());
                if (!from.isBefore(to)) {
                    errors.add("from must be before to");
                }
            } catch (DateTimeParseException ignored) {
                errors.add("Invalid timestamp format");
            }
        }

        if (request.limit() != null && (request.limit() < 1 || request.limit() > config.maxQueryResults())) {
            errors.add("limit must be between 1 and " + config.maxQueryResults());
        }

        if (request.timeout_ms() != null && (request.timeout_ms() < 1 || request.timeout_ms() > config.maxQueryTimeoutMs())) {
            errors.add("timeout_ms must be between 1 and " + config.maxQueryTimeoutMs());
        }

        return errors;
    }
}
