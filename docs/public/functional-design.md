# BlockLog: Functional Design

Status: proposed sprint baseline, 2026-09-12, updated 2026-09-14. Behavior below is an implementation contract, not a claim of completed functionality. Flush boundary semantics require the decision recorded in the technical design.

## Purpose

Help an incident responder ingest logs quickly and retrieve relevant messages by tenant, event time, tags, and literal text. Optimize storage writes while accepting that broad searches must scan and decompress more data.

"Index-free" means no full-text inverted index. A lightweight block metadata catalog remains part of the design. The message body stays opaque during ingestion; only the structured envelope is validated.

## Sprint scope

| Included | Deferred |
| --- | --- |
| Single process, single local data directory | Clustering, replication, sharding |
| Batched ingestion, bounded buffers, LZ4 blocks | Kafka, agents, external ingestion connectors |
| Tenant/time/tag pruning and literal substring search | Regex, SQL, rankings, aggregations |
| CRC32 validation, restart discovery, partial-result reporting | WAL, exactly-once delivery, power-loss guarantees |
| Search UI with structured form and natural-language mode | Billing, SSO, user administration |
| Status view, `/help`, `/onboarding` | Conversational query refinement, log analysis, anomaly detection |
| JUnit, JMH, integration evidence, Micrometer | Production SLA, automatic retention/compaction |

Tenant filtering is mandatory and tested, but a client-supplied tenant identifier is not authentication. The sprint deployment is local/trusted. Public multi-tenant access requires a separately designed authentication and authorization boundary.

## User journeys

1. Onboarding explains the data format and buffered acknowledgement, submits a small synthetic batch, waits for persistence, and runs an example search using both the structured form and the natural-language input.
2. A responder selects a tenant, UTC time range, optional exact tags, and a literal search string using the structured form. Results show event time, tags, and message text.
3. A responder types a plain-English query in the natural-language input. The system translates it to a structured search query, shows the generated parameters for review and editing, and executes against the same search API. Results display identically.
4. A query with damaged candidate blocks still returns healthy matches, with a visible incomplete-results warning and skipped-block count.
5. A status view shows accepted/persisted records, queue pressure, flush failures, and actual measured query timings. It must not display invented benchmark numbers.
6. Help explains search semantics, both input modes, eventual visibility, partial results, overload responses, and durability limitations.

## Proposed HTTP contract

Paths and fields should be frozen with API tests on Day 1. This table describes planned routes; none exists yet.

| Operation | Contract |
| --- | --- |
| `POST /api/v1/logs` | Body contains `tenant_id` and `records`; each record has `timestamp`, `tags`, and `message`. Validate and reserve capacity for the whole bounded batch before accepting it. Return `202` with `accepted_records` and `durability: buffered`. |
| `POST /api/v1/search` | Require `tenant_id`, `from`, `to`; optional `tags`, `text`, `limit`, `timeout_ms`. Return matches and execution/completeness information. |
| `GET /api/v1/status` | Return engine readiness, buffer usage, persistence counters, and recovery health without exposing message bodies or tenant inventories. |

Use ISO-8601 UTC input timestamps with millisecond precision; reject invalid timestamps and `from >= to`. Query ranges are `[from, to)`. Out-of-order events are allowed. Tags are exact, case-sensitive key/value pairs combined with AND. Text is a case-sensitive literal substring of one decoded message, without Unicode normalization; an omitted/empty text filter matches all messages passing the other predicates.

Initial configurable limits: 1,000 records and 5,000,000 raw HTTP body bytes per batch; 64 KiB encoded record; 16 tags per record; 256 UTF-8 bytes per tenant, tag key, or tag value; 1,024 UTF-8 bytes of query text; result limit 100 by default, maximum 1,000; query deadline 5 seconds by default, maximum 30 seconds. Byte units: MB = 1,000,000 and KiB = 1,024. These are resource guards to validate, not measured optimal settings.

Return `400` for invalid fields, `413` for size limits, `429` for temporary admission pressure, and `503` when persistence is unhealthy or the engine is not ready. No silently discarded accepted records. Retries can create duplicates; deduplication is outside scope. Do not return success for a batch that was only partly admitted.

## Natural-language query mode

The frontend provides an alternative search input where the user types a plain-English query instead of filling out the structured form. The system translates the natural language into the structured `POST /api/v1/search` request body using a language model API call, then executes against the same search endpoint.

### Flow

1. The user types a query such as "show me payment-service errors from the last 2 hours with tag env=prod" in the NL input.
2. The frontend sends the query string to the language model API with a system prompt describing the search schema, field types, constraints, and known tenant names.
3. The model returns a JSON object matching the search request body (`tenant_id`, `from`, `to`, optional `tags`, `text`, `limit`, `timeout_ms`).
4. The frontend validates the JSON against the schema (required fields present, correct types, `from < to`, valid ISO-8601 timestamps).
5. A query preview card shows the parsed parameters. The user can edit any field before executing.
6. On confirmation, the frontend calls `POST /api/v1/search` and renders results identically to a structured-form search.

### Failure handling

- LLM timeout (hard limit: 8 seconds): show "Translation timed out. Try a shorter query, or use the structured form."
- LLM network error: show "Could not reach the translation service. Use the structured form."
- Unparseable or invalid JSON from LLM: show "Couldn't interpret that query. Try rephrasing, or use the structured form."
- Search API returns 400 on the generated query: show the validation error and the structured form pre-filled with whatever fields parsed successfully, so the user can correct and retry.
- In all failure cases, the structured form remains available. NL search never blocks the primary search path.

### Constraints

- Only the user's query string is sent to the LLM. No log contents, search results, tenant data, or system internals are included.
- Relative time expressions ("last 2 hours", "yesterday") are resolved to absolute ISO-8601 timestamps by the system prompt instructing the model to use the current UTC time provided in the prompt.
- The LLM has no memory across queries. Each translation is a single stateless API call.
- The structured form is the default mode. NL search is opt-in via a toggle or tab.

## Search visibility and completeness

- Search only a snapshot of published immutable blocks taken at query admission; buffers and later publications are excluded.
- Normal visibility follows the flush interval plus queue and disk time. Five seconds is a flush trigger, not a hard end-to-end visibility guarantee.
- Return `results`, `returned_count`, `partial`, `truncated`, `timed_out`, `skipped_blocks`, `unavailable_blocks`, `candidate_blocks`, `scanned_blocks`, and `elapsed_ms`.
- Define `scanned_blocks` as successfully searched blocks. `skipped_blocks` counts candidate files that failed reading/validation. `unavailable_blocks` counts files found unreadable during recovery whose relevance cannot safely be determined.
- Set `partial=true` for skipped/unavailable blocks, deadline expiry, or unrecovered catalog state. Never represent these as a complete zero-match result.
- Healthy completed searches return the earliest K matches by `(timestamp, block_id, record_ordinal)`. `truncated=true` means more than K matches were found. Do not stop at the first K task completions. A timed-out query returns available matches with `partial=true`, without promising a global earliest-K result.
- Deadlines cover admission, pruning, waiting, decompression, and gathering. Check cancellation between blocks and periodically while scanning records. Bound result bytes as well as result count.

## Acceptance criteria

1. Valid UTF-8 records round-trip with timestamps and tags intact, including multiline and non-ASCII messages.
2. Tenant/time/tag predicates return the same matches as a simple reference scan; no cross-tenant results and no pruning false negatives.
3. Size and age triggers flush records once, with explicit overload and shutdown behavior.
4. Published blocks remain discoverable after process restart; incomplete temporary files do not become searchable.
5. Damaged payloads or headers cannot cause unchecked allocation, abort all healthy blocks, or silently imply complete results.
6. Concurrency, buffering, metadata, and results have configured limits and observable overload behavior.
7. The interface works against the real API and includes loading, empty, failure, overload, and partial-result states.
8. Both structured-form and natural-language searches produce identical results for equivalent queries against the real API.
9. NL search fails gracefully on LLM timeout, network error, or invalid model output, with a clear message and access to the structured form.
10. All four headline metrics have reproducible evidence, or are explicitly marked unmeasured. A 95% pruning rate is a workload-specific target.
