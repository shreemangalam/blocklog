package com.blocklog.ingest;

/** Persistence is unhealthy or the engine is not ready. Maps to HTTP 503. */
public class IngestionUnavailableException extends RuntimeException {
    public IngestionUnavailableException(String message) { super(message); }
}
