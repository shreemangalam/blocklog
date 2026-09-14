# BlockLog: Technical Design

Status: proposed sprint baseline, 2026-09-12, updated 2026-09-14. No implementation has been verified. Recommendations below make the original thesis implementable within one week; the flush interpretation is explicitly unresolved.

## Architecture and ownership

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

Keep the engine independent of Spring. One Maven module is enough. Suggested packages under `com.blocklog`: `model`, `ingest`, `storage`, `metadata`, `search`, `api`, and `observability`. Tests mirror packages. Put resources in `src/main/resources`, deterministic fixtures in `src/test/resources`, and JMH sources in `src/jmh/java` with an explicit Maven benchmark profile. Do not create abstraction layers without a concrete caller.

## Decisions and limits

| Concern | Proposed baseline |
| --- | --- |
| Deployment | One writer process with an exclusive data-directory lock; local filesystem |
| Ingestion queue | Bounded, established lock-free MPSC ring implementation; choose and pin dependency on Day 1 |
| Buffer ownership | One consumer owns mutable buffers; transfer sealed buffers to writer without concurrent reuse |
| Block grouping | One tenant per block; min/max event time and bounded tag-pair union |
| File unit | One immutable file per block for the sprint; larger segment files deferred |
| Compression | Independent raw LZ4 payload with a versioned BlockLog header |
| Durability | Buffered acknowledgement, no WAL; publication after file force |
| Search | Virtual-thread executor with bounded submitted work, active queries, and global scan permits |
| Result policy | Earliest K by stable order, with explicit incompleteness and truncation |
| NL search | Frontend-only LLM translation; no backend involvement; structured API is the execution path |

Initial tuning proposals: 64 MiB queued encoded records, 64 MiB active buffers, 16 MiB sealed buffers, four admitted queries, scan permits `max(1, availableProcessors - 1)`, and 64 MiB retained results globally. Count retained batch payloads once and transfer byte reservations with ownership. Limit concurrent request parsing and reserve capacity before materializing large bodies. Queue slot bounds alone do not bound bytes. Reject new ingestion when the persistence backlog exhausts its budget.

Set a finite block/catalog ceiling, initially 10,000 blocks including reserved publications, and stop ingestion before exceeding it. This is a sprint dataset ceiling, not a capacity claim. Account for catalog/tag object overhead and live mmap regions separately in evidence. Adding retention or removing this ceiling requires a new resource-lifecycle design.

## Flush semantics: decision required before codec implementation

Original requirement: exactly 5 MB or 5 seconds. Decimal 5 MB means 5,000,000 encoded, uncompressed record bytes. Complete variable-length records generally cannot fill that boundary exactly.

Recommended interpretation: 5,000,000-byte maximum payload target; flush immediately on equality, or flush the current nonempty buffer before appending a record that would exceed it. Never split a record. Reject records over the 64 KiB cap. Time-flush a nonempty buffer when its oldest accepted record reaches five seconds using a monotonic clock. Queue residence counts toward age; observe deadlines even during continuous traffic. Empty buffers do not generate blocks.

This changes literal byte-exact filling and must be resolved before writing format fixtures. If byte-exact blocks are mandatory, specify fragmentation/reassembly or padding and its accounting instead. Do not silently implement either interpretation. Under global memory pressure, reject/backpressure additional ingestion rather than evict unflushed records. Release empty tenant buffers after publication.

Inject the clock for unit tests. Triggering at five seconds does not imply the operating system will schedule or finish disk I/O at exactly that time; report trigger lag and publication latency separately.

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

Maintain immutable block entries containing internal file ID, tenant, min/max timestamp, and a bounded union of exact tag pairs. Tenant and time pruning are exact at block granularity. Tag unions can produce false positives when pairs occur on different records; apply predicates again to each decoded record.

If a tag summary exceeds its cap, mark it saturated and disable tag pruning for that block. Never drop tag pairs and then treat the incomplete set as exhaustive. Unknown metadata must produce scan-or-unavailable behavior, not exclusion. For a query `[from,to)`, reject a block only when `max_timestamp < from` or `min_timestamp >= to`.

Prune using resident catalog entries before opening payload files. Startup still reads headers, so "before touching disk" describes normal query candidate selection, not the entire engine lifecycle. The 95% pruning figure is a target on selective workloads, not an invariant.

## Search execution and mapped files

Use `Executors.newVirtualThreadPerTaskExecutor()` for structured task ownership and scatter/gather orchestration. Avoid one submitted task per block over an unbounded catalog: submit a bounded worker group that pulls candidate IDs. Enforce a global scan limit across queries and a separate active-query limit. Acquire permits before allocating decompression buffers; release them in `finally` after the task has actually stopped.

Virtual threads are not intended to accelerate long CPU-intensive operations. Bounded CPU parallelism and pruning are the performance hypotheses here; compare worker settings empirically. See [Java 21 virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html).

Map finalized files read-only, with independent buffer views per reader. Never truncate or mutate a mapped published file. Use one shared mapping per block and account for its lifetime against the finite block ceiling; a per-query remap can accumulate mappings faster than collection. Closing a `FileChannel` is not an explicit unmap. Java documents mapping lifetime as tied to buffer garbage collection; see [MappedByteBuffer](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/MappedByteBuffer.html).

Do not rely on unsupported cleaners or preview APIs. Keep deletion/retention outside this sprint and test Windows behavior. Measure mapped/resident memory and native overhead; mmap is neither zero-memory nor guaranteed disk-free access. Validate files before starting corruption fixtures; never truncate files while live readers hold mappings.

Each worker maintains a capped earliest-K heap and match count; the coordinator merges under a global byte reservation. Stream completed worker output into the bounded merge rather than retaining all block matches. If result-byte capacity is exhausted, end with explicit partial/resource-limit information. Timeout cancels outstanding tasks; check interruption during scanning. Do not catch `Throwable` or treat programming defects as ordinary corrupt blocks.

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

Expose low-cardinality Micrometer counters/gauges/timers for accepted and published bytes/records, queue and buffer bytes, flush reasons/lag/failures, candidates/pruned/scanned/skipped blocks, unavailable files, query durations/timeouts, active queries, and scan permits. Avoid tenant IDs, arbitrary tags, and messages as metric labels.

See [testing evidence](testing-evidence.md) for failure injection, reference-search equivalence, concurrency tests, and measurement definitions. JMH measures isolated hot paths; an end-to-end harness measures sustained persistence, p95 API search latency, and process memory.
