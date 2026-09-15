package com.blocklog.ingest;

import com.blocklog.model.LogRecord;

import java.util.List;

/**
 * A whole-batch admission unit carried through the MPSC queue. The
 * accompanying {@code reservedBytes} was already deducted from the byte
 * budget by the enqueuing thread; the consumer releases it after each
 * record is added to a tenant buffer (buffer bytes then become the
 * new owner).
 */
public record IngestBatch(String tenantId, List<LogRecord> records, long reservedBytes) {}
