# BlockLog: Technical Design

Status: 2026-09-18. Baseline shipped: 30 JUnit + integration tests pass, `/actuator/prometheus` is live, and the four sprint metrics have measured numbers in [testing evidence](testing-evidence.md). Flush semantics resolved. This document describes what the engine currently implements; sections that still describe design candidates are marked "proposed" explicitly.

## Architecture and ownership

![Architecture](architecture.svg)

```text
HTTP envelope validation -> bounded MPSC ring -> single ingestion owner
  -> per-tenant buffers -> bounded flush handoff -> LZ4 + CRC32
  -> temporary block file -> force/close -> atomic publication -> catalog

Search request -> admission + catalog snapshot -> conservative metadata pruning
  -> bounded virtual-thread workers -> read-only mmap -> validate + decompress
  -> exact record filters -> bounded deterministic result merge

NL query (frontend only):
  user text -> LLM API (schema prompt + query string) -> JSON response
  -> client-side validation -> POST /api/v1/search -> standard result render
```

Shipped packages under `com.blocklog`: `model`, `ingest`, `storage`, `metadata`, `search`, `api`, `observability`, plus `demo` (measurement CLIs — `SyntheticIngest`, `MeasureSearch`, `MeasureCompression`, `MeasureMemory`). Tests mirror packages. JMH sources are under `src/jmh/java`, activated by `-Pjmh`.

## Decisions and limits

| Concern | Shipped design |
| --- | --- |
| Deployment | One writer process; local filesystem. Directory lock is a proposed follow-up. |
| Ingestion queue | jctools `MpscUnboundedArrayQueue` (unbounded structurally, bounded by `ByteBudget` — see below) |
| Buffer ownership | Single consumer thread drains the queue into per-tenant `TenantBuffer` objects; no shared mutation |
| Block grouping | One tenant per block; min/max event time and bounded tag-pair summary |
| File unit | One immutable file per block, name `<uuid>.blk` |
| Compression | Raw LZ4 payload with a versioned BlockLog header, CRC32 on header and payload |
| Durability | Buffered `202 accepted`; no WAL. Publication after `FileChannel.force(true)` + atomic rename |
| Search | Virtual-thread-per-task scan of pruned candidates; deadline in nanoseconds; bounded active queries + scan permits |
| Backpressure | Typed `IngestionOverloadException` → HTTP 429, `IngestionUnavailableException` → HTTP 503 |
| Result policy | Earliest K by (timestamp, block, record) order via a min-heap; `truncated=true` when `totalHitsSeen > returned` |
| Mmap lifecycle | Caffeine LRU cache in `BlockCatalog` bounded by `mmap-cache-size` (default 256); removal listener closes the FileChannel |
| NL search | Frontend-only LLM translation; no backend involvement; structured API is the execution path |

Current tuning (`application.yml`): 64 MiB queue byte budget, 64 MiB buffer byte budget, 4 admitted queries, scan permits `max(1, availableProcessors − 1)`, 256-entry mmap cache, 5 MB / 5 s flush trigger. `EngineConfig.resultBytesBudget` was declared but never enforced — removed in `57447bf` so the config doesn't lie about limits it doesn't implement. If a per-query result byte cap is wanted later, add it with actual enforcement.

### ByteBudget: whole-batch reservation with ownership transfer

`com.blocklog.ingest.ByteBudget` is a single-pool atomic byte reservation. HTTP threads reserve capacity on the **queue** budget for a whole batch before enqueuing; the consumer thread releases queue-budget bytes and reserves the same bytes on the **buffer** budget as it moves records into per-tenant buffers. The flush pipeline releases buffer bytes after the block is durably published. Byte reservations transfer with ownership rather than being counted independently at each stage; that's what makes the pipeline's memory footprint predictable.

If either budget can't cover a batch, `ByteBudget.tryAcquire` returns false and the controller maps to HTTP 429. This replaces the earlier `IllegalStateException`-with-string-match approach with typed exceptions; the mapping is proven end-to-end by `HttpApiIntegrationTest.oversizedBatchReturns429WhenQueueBudgetExhausted` and `IngestionUnhealthyIntegrationTest.ingestReturns503AfterPersistenceFailure`.

Set a finite block/catalog ceiling, initially 10,000 blocks including reserved publications, and stop ingestion before exceeding it. This is a sprint dataset ceiling, not a capacity claim. Account for catalog/tag object overhead and live mmap regions separately in evidence. Adding retention or removing this ceiling requires a new resource-lifecycle design.

## Flush semantics: resolved (record-aligned)

Shipped interpretation: **5,000,000-byte target, record-aligned, flush-before-overflow, never split a record**. `TenantBuffer.wouldExceedSize(nextRecordBytes)` returns true when appending would push the buffer past the target; the flush fires before the append. Records over 64 KiB (`max-record-bytes`) are rejected at admission. Empty buffers do not generate blocks.

Age flush is a 1-second scheduled tick against the oldest record's `System.currentTimeMillis()`; when `now - oldestRecordEpochMs >= 5000 ms`, the buffer flushes. Queue residence counts toward age (the timestamp is set at record enqueue, not at flush time).

Triggering at 5 s does not imply the OS will finish disk I/O at 5 s. Trigger latency and publish latency are separate; the shipped `EngineMetrics.flush.duration` timer captures publish time, and the run bundle in `testing-evidence.md` reports overall throughput across a full 5-second buffer window.

## Record and block format

Freeze byte-level layout, endian order, size limits, and golden fixtures before persistence code. Proposed v1 uses big-endian integers and UTF-8 strings. Each record is length-prefixed and includes event timestamp (signed epoch milliseconds), bounded length-prefixed tag pairs, and message bytes. Tenant identity is stored once in the enclosing block header. Record lengths prevent matching across record boundaries.

Proposed file layout:

```text
magic | version | header_length | compressed_length | raw_length | record_count
min_timestamp | max_timestamp | payload_crc32 | bounded tenant/tag metadata
header_crc32 | compressed_payload
```

The header checksum covers all preceding header bytes, including payload checksum and metadata; payload CRC32 covers the compressed bytes and is checked before decompression. This catches accidental damage, not malicious tampering. Choose fixed field widths and caps in the codec specification; do not infer them from this diagram.

Validate file size and minimum prefix before reading lengths. Treat every length as untrusted: check nonnegativity, overflow, format caps, header cap (proposed 64 KiB), raw cap, and LZ4 compressed-size bound before allocating or mapping. Verify header CRC before using metadata for pruning; then require exact file-length consistency. After decompression, verify output length, record framing, record count, and metadata consistency. Reject unknown format versions.

Raw LZ4 blocks do not supply a self-contained framing layer; the application must define its metadata and framing. See the [LZ4 block specification](https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md).

One file per block makes the next file independently discoverable even if a header is destroyed. CRC32 alone does not establish the next block offset in a multi-block segment. The tradeoff is filesystem and mapping overhead as block counts grow, which must be measured.

## Publication, restart, and failure behavior

1. Generate a unique internal block ID; never form paths directly from user tenant/tag values.
2. Write a temporary file in the destination directory; handle short writes in a loop.
3. Force the file contents and metadata, close the writer, then atomically move to a unique final filename on the same filesystem. Require atomic-move support; fail initialization on unsupported configurations instead of quietly weakening publication semantics.
4. Publish immutable validated metadata to the in-memory catalog only after the final file exists. Advance persistence counters and release the buffer after success.
5. If publication has an ambiguous outcome, reconcile that block ID before any retry. Never create another ID for the same sealed block as an automatic retry.
6. On startup, acquire the writer lock and discover final files. Rebuild the catalog from bounded, checksum-validated headers; ignore temporary files and report them. Files with invalid headers become unknown-relevance unavailable blocks. Surface this condition on status and search responses.
7. Payload validation occurs during search; known damaged files remain represented as unavailable rather than disappearing from completeness accounting. A crash after rename but before catalog insertion is recovered by directory discovery.

Use strict publication as a process-crash recovery contract. File forcing and atomic rename do not by themselves establish a portable sudden-power-loss guarantee for directory entries. No such guarantee is claimed in this sprint. `202` means in-memory acceptance; a crash can lose queued, active, or unpublished records, potentially older than five seconds under backlog.

On disk-full or write failure, retain accepted buffers within their reserved budget, mark persistence unhealthy, and reject further ingestion. Do not claim data persisted or retry in an unbounded loop. Graceful shutdown stops admission, drains accepted work within a documented deadline, flushes remaining buffers, and reports failure if it cannot persist them.

## Metadata pruning

`BlockCatalog.candidatesForQuery` returns a `PruneResult(candidates, prunedCount)` where `prunedCount` sums tenant / time / tag drops. Tenant and time are exact; tag unions can produce false positives when pairs occur on different records, so predicates run again per decoded record. Saturated tag summaries disable tag pruning for that block; unavailable blocks stay in the catalog and count against completeness but drop out of candidates.

For a query `[from,to)`, a block is rejected when `max_timestamp < from` or `min_timestamp >= to`. Coverage proven by `SearchEngineTest.tenantAndTimePruningAreExact` and, at the HTTP layer, by `HttpApiIntegrationTest.searchWithWrongTenantReturnsEmpty`.

Measured prune rate is workload-dependent. Cross-tenant queries in the 2026-09-16 run bundle achieved **33.3 %** (1 of 3 blocks pruned by tenant). Within a single tenant, uniform-random 24-hour synthetic data doesn't prune further because every block spans the full time window and contains every level — a workload-shape observation, not an engine limitation. Streaming ingest (narrow time-range blocks) would push time-pruning much higher. The 95 % pruning figure is a target on selective incident workloads, not an invariant, and remains unmeasured under real streaming traffic.

## Search execution and mapped files

`SearchEngine` uses `Executors.newVirtualThreadPerTaskExecutor()`. Global `queryPermits` semaphore (default 4) bounds concurrent queries at the entry point via `tryAcquire()` — starved queries return `timed_out=partial` immediately rather than blocking. Global `scanPermits` semaphore (default `max(1, availableProcessors − 1)`) bounds concurrent block scans; each scan acquires with `tryAcquire(remainingNanos, NANOSECONDS)` so a starved scan gives up by the query's own deadline instead of blocking. That fix (`feefd93`) is exercised by `SearchEngineTest.queryReturnsTimedOutInsteadOfBlockingUnderScanPermitStarvation`.

The deadline is set once in nanoseconds and checked at three points: between blocks in the main future-drain loop, at the top of every scan before acquiring a mapping, and every 256 records inside a scan loop. Timeout cancels every outstanding future so scans return cleanly rather than being abandoned.

Virtual threads are not a CPU speedup; bounded CPU parallelism and pruning are the performance hypotheses. See [Java 21 virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html).

### Bounded mmap cache

Mapped files use `BlockMapping`, a wrapper around a read-only `FileChannel` + `MappedByteBuffer`. Each reader takes a `duplicate()` view so concurrent scans don't share position/limit. `BlockCatalog` caches mappings in a Caffeine `Cache<String, BlockMapping>` bounded by `mmap-cache-size` (default 256); the eviction listener closes the FileChannel. Concurrent scanners already holding a `duplicate()` view keep working — the mapping lifetime is tied to garbage collection of the `MappedByteBuffer`, not to the channel. Published block files are never mutated or deleted during a live run, which is what makes bounded caching safe.

This closes the "unbounded FileChannel accumulation on large catalogs" concern earlier notes flagged. Coverage: `MmapCacheEvictionTest` (cache stays bounded under repeated misses, reopening evicted blocks still reads correctly, `markUnavailable` invalidates the cache entry).

### Result merge

Each scan returns matches into a shared `PriorityQueue` bounded by the requested `limit` (min-heap ordered by timestamp, reversed). `totalHitsSeen` counts every candidate match observed across scans; `truncated=true` when it exceeds `returned`. Failed scans (corruption, mapping load failure) count into `skipped_blocks`; timed-out scans set `timed_out`; `partial=true` fires whenever `scanned < candidates`, or any scan skipped, or any block was already unavailable at query start, or the query timed out. Every response carries all five counts so a client can always tell what actually happened.

`RuntimeException`-typed defects propagate; corruption paths catch `BlockReader.BlockCorruptException` specifically and degrade the block to unavailable rather than treating a programming bug as ordinary data damage.

## LLM-assisted query translation (frontend only)

This component sits entirely in the Next.js frontend. No backend changes are required. It translates a user's natural-language incident query into the structured JSON body expected by `POST /api/v1/search`.

### Architecture

```text
User types NL query
  -> React hook (useNLSearch)
  -> Anthropic Messages API (single stateless call)
     System prompt: search schema, field types, constraints, current UTC time, known tenants
     User message: the raw query string
     Response format: JSON matching search request body
  -> Client-side JSON validation (required fields, types, from < to)
  -> Query preview card (user can edit)
  -> POST /api/v1/search (identical to structured-form path)
  -> Standard result rendering
```

### System prompt contract

The system prompt provides:
- The exact field names and types of the search request body (`tenant_id`: string required, `from`: ISO-8601 required, `to`: ISO-8601 required, `tags`: object optional, `text`: string optional, `limit`: integer optional, `timeout_ms`: integer optional).
- Field constraints from the API contract (max 1,000 limit, max 30,000 timeout_ms, `from < to`, tag format).
- The current UTC timestamp so the model can resolve relative time expressions.
- Known tenant identifiers (injected from a local config or the status endpoint) so the model can match informal tenant references.
- An instruction to respond with valid JSON only, no markdown fencing, no explanation.

### What is NOT sent to the LLM

- No log contents, search results, or message bodies.
- No system internals, file paths, block metadata, or engine state.
- No user identity, credentials, or API keys (the key authenticates the API call itself, not as message content).

### Failure modes and fallback

| Failure | Frontend behavior |
| --- | --- |
| LLM call exceeds 8-second timeout | Abort, show timeout message, offer structured form |
| Network error reaching LLM API | Show network error message, offer structured form |
| LLM returns non-JSON or malformed JSON | Show parse error message, offer structured form |
| LLM returns valid JSON but search API returns 400 | Show validation error, pre-fill structured form with valid fields |
| LLM returns a query the user wants to adjust | Query preview is editable; user modifies and executes |

### Scope boundaries

- No conversational memory or multi-turn refinement. Each NL query is an independent translation.
- No log analysis, anomaly detection, summarization, or pattern recognition.
- No backend proxy for the LLM call in this sprint. The API key is a browser-side environment variable. Document that production deployment requires a backend proxy to protect the key.
- If the LLM feature is unavailable (no API key configured), the NL tab is hidden and the structured form is the only mode. The project functions fully without it.

## Observability and verification

`com.blocklog.observability.EngineMetrics` registers Micrometer counters, gauges, and timers with the injected `MeterRegistry`. Labels never carry tenant IDs, tag values, or message content — only the low-cardinality `reason` tag on the flush counter (`size|age`). Shipped instruments:

- Counters: `blocklog.ingest.accepted.records`, `blocklog.ingest.accepted.bytes`, `blocklog.ingest.rejected`, `blocklog.persist.records`, `blocklog.persist.bytes`, `blocklog.flush.by{reason}`, `blocklog.flush.failures`, `blocklog.blocks.published`, `blocklog.search.blocks.{candidates,scanned,pruned,skipped}`, `blocklog.search.queries.timed_out`, `blocklog.search.queries.rejected{reason=active_limit}`.
- Timers: `blocklog.flush.duration`, `blocklog.search.duration`.
- Gauges: `blocklog.queue.bytes`, `blocklog.buffer.bytes`, `blocklog.search.queries.active`, `blocklog.search.scan_permits.free`, `blocklog.catalog.unavailable`.

Prometheus scrape at `/actuator/prometheus`; `HttpApiIntegrationTest.prometheusEndpointExposesEngineMetrics` pins the mangled counter names (`blocklog_ingest_accepted_records_total`, `blocklog_flush_by_total{reason="size"}`) so the tag contract survives future refactors. In Spring Boot Test scope, the endpoint requires an explicit `management.prometheus.metrics.export.enabled=true` override; the runtime autoconfig handles it in production.

See [testing evidence](testing-evidence.md) for the measured bundle (ingestion rate, compression ratio, search latency percentiles, memory delta, JMH codec throughput) with reproduction commands.
