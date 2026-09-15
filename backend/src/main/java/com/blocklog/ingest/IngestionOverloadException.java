package com.blocklog.ingest;

/** Transient overload — retry after backoff. Maps to HTTP 429. */
public class IngestionOverloadException extends RuntimeException {
    public IngestionOverloadException(String message) { super(message); }
}
