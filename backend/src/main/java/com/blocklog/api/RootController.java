package com.blocklog.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.boot.web.servlet.error.ErrorController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/**
 * Landing + error handling for the backend HTTP surface.
 *
 * Before this existed, a browser hitting {@code http://localhost:8080} got
 * Spring Boot's Whitelabel Error Page — a real "this doesn't look organized"
 * moment for an operator or an interviewer. Now the root returns a small
 * JSON manifest of the API surface; a bare {@code /error} returns the same
 * shape as any other 4xx/5xx with a machine-readable body, no whitelabel.
 */
@Controller
public class RootController implements ErrorController {

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> index() {
        return Map.of(
                "service", "blocklog",
                "description", "Index-free log storage engine for incident-response search",
                "version", "0.1.0-SNAPSHOT",
                "endpoints", List.of(
                        Map.of("method", "POST", "path", "/api/v1/logs",
                                "purpose", "Ingest a batch of records (202 buffered)"),
                        Map.of("method", "POST", "path", "/api/v1/search",
                                "purpose", "Query blocks by tenant / time / tags / text"),
                        Map.of("method", "GET",  "path", "/api/v1/status",
                                "purpose", "Engine health, counters, buffer pressure"),
                        Map.of("method", "GET",  "path", "/actuator/health",
                                "purpose", "Spring liveness/readiness"),
                        Map.of("method", "GET",  "path", "/actuator/metrics",
                                "purpose", "Micrometer meter listing"),
                        Map.of("method", "GET",  "path", "/actuator/prometheus",
                                "purpose", "Prometheus scrape endpoint")
                ),
                "docs", Map.of(
                        "readme", "https://github.com/shreemangalam/blocklog",
                        "help_ui", "/../help — served from the frontend, not this port"
                )
        );
    }

    /**
     * Replaces Spring Boot's Whitelabel Error Page. Returns a small JSON
     * body carrying the status code, error phrase, and request path.
     * Content negotiation still applies so tools that ask for HTML get an
     * HTML snippet.
     */
    @GetMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> errorJson(HttpServletRequest request) {
        int status = extractStatus(request);
        String path = extractPath(request);
        return ResponseEntity.status(status).body(Map.of(
                "status", status,
                "error", HttpStatus.valueOf(status).getReasonPhrase(),
                "path", path,
                "hint", "This backend serves /api/v1/*, /actuator/*, and / (this manifest). "
                        + "For the UI, use the frontend."
        ));
    }

    @GetMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorHtml(HttpServletRequest request) {
        int status = extractStatus(request);
        String path = extractPath(request);
        String phrase = HttpStatus.valueOf(status).getReasonPhrase();
        String body = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <title>BlockLog %d</title>
                <style>
                  body{font:14px/1.5 ui-sans-serif,system-ui,sans-serif;color:#0f172a;
                       background:#f8fafc;margin:0;padding:48px;max-width:640px}
                  h1{font-size:20px;margin:0 0 8px}
                  code{background:#e2e8f0;padding:2px 6px;border-radius:4px}
                  a{color:#4f46e5}
                </style></head><body>
                <h1>%d %s</h1>
                <p><code>%s</code> is not served by this backend.</p>
                <p>Available: <a href="/">/</a>, <code>/api/v1/logs</code>,
                <code>/api/v1/search</code>, <code>/api/v1/status</code>,
                <code>/actuator/prometheus</code>.</p>
                <p>For the human UI, use the frontend (default: <code>http://localhost:3000</code>).</p>
                </body></html>
                """.formatted(status, status, phrase, path);
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    private int extractStatus(HttpServletRequest request) {
        Object attr = request.getAttribute("jakarta.servlet.error.status_code");
        return attr instanceof Integer i ? i : 500;
    }

    private String extractPath(HttpServletRequest request) {
        Object attr = request.getAttribute("jakarta.servlet.error.request_uri");
        return attr instanceof String s ? s : request.getRequestURI();
    }
}
