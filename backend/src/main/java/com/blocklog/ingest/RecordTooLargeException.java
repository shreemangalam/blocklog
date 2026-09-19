package com.blocklog.ingest;

/** Record exceeds the configured per-record byte limit. Maps to HTTP 400. */
public class RecordTooLargeException extends RuntimeException {
    public RecordTooLargeException(String message) { super(message); }
}
