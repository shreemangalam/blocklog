# BlockLog: Testing Evidence

Status: protocol only, 2026-09-12, updated 2026-09-14. No engine tests or benchmarks have run. Empty build and CI files are not evidence of passing checks. Replace pending entries only with actual saved results.

## Headline measurements

| Metric | Definition | Result |
| --- | --- | --- |
| Ingestion MB/s | Successfully published uncompressed encoded record bytes / elapsed seconds / 1,000,000, including final drain and file force | Unmeasured |
| Compression ratio | Uncompressed encoded record bytes / total published block-file bytes, including headers; higher is better | Unmeasured |
| p95 search latency | 95th percentile of client-observed request-to-complete-response times for a named workload | Unmeasured |
| Memory overhead | Idle and loaded heap, process resident/working-set memory, native/direct/mapped metrics, and loaded-minus-idle deltas under a fixed workload | Unmeasured |

Report accepted MB/s separately from persisted MB/s; draining an ever-growing memory queue is not sustained storage throughput. Also report source message bytes versus encoded bytes so framing and tag overhead remain visible. A compressed-payload-only ratio may be supplemental, not a substitute for the total-file ratio.

For p95, save raw samples and state the percentile estimator, sample count, throughput, concurrent clients, result limit, timeout count, partial/error count, and cache condition. Use nearest-rank p95 on sorted samples (`ceil(0.95 * N)`, one-based). Report all-request latency plus successful-complete latency separately; never silently exclude failed or timed-out requests. A fast incomplete query is not a successful complete query.

For memory, record JVM heap settings, a fixed sampling interval (proposed one second), steady-state median, and peak. On Windows name the exact counters, such as Working Set and Private Bytes; on Linux name RSS/PSS measurements. Do not sum overlapping heap, resident, and mapped counters. Mapped virtual address space is not resident memory; heap alone is not process memory. Report GC time/allocation separately where available.

## Correctness and failure matrix

| Area | Required cases | Evidence status |
| --- | --- | --- |
| Record codec | Empty/multiline/UTF-8 messages, round-trip tags/time, record boundaries, maximum/rejected lengths, golden format fixture | Pending |
| LZ4 blocks | Compressible and random payloads, checked length arithmetic, unsupported version, header/payload bit flips, truncated file | Pending |
| Flushing | Exact threshold, next-record overflow under chosen policy, oldest age with injected clock, continuous traffic, idle tenant, no empty block | Pending |
| Admission | Whole-batch reservation, concurrent producers, full queue, byte caps, no silently lost/duplicated admitted sequence IDs | Pending |
| Publication | Short writes, failed force/move, unsupported atomic move, disk-full injection, unique IDs, ambiguous retry reconciliation | Pending |
| Restart | Process termination before/after publication, orphan temp file, rebuild without cached catalog, exclusive directory lock | Pending |
| Pruning | Cross-tenant isolation, time edges, out-of-order events, tag union false positives, saturated summaries, corrupted metadata | Pending |
| Search | Reference-scan equivalence, match at message edges, no cross-record match, bounded earliest-K ordering, equal timestamps, zero matches | Pending |
| Concurrency | Concurrent ingest/search snapshots, global permits across queries, cancellation cleanup, bounded results and backlog | Pending |
| Partial results | One bad block among healthy files, unknown-relevance recovery failure, timeout, missing published file, visible UI warning | Pending |
| Lifecycle | Graceful drain, failed shutdown reported, restart on Windows, stable mapping reuse over repeated searches | Pending |
| API/UI | Validation/413/429/503, real ingest-to-search journey, loading/empty/error states, `/help`, `/onboarding` | Pending |
| NL search | Valid translation, malformed LLM output, LLM timeout, network failure, search API rejection of generated query, structured-form fallback, no data leakage | Pending |

Use JUnit 5 for unit and integration checks. Use temporary directories for storage tests and subprocesses for crash/restart tests. Add Testcontainers for packaged service smoke tests once an application image and Docker runtime exist; do not add a database dependency merely to exercise Testcontainers. Tests must verify outcomes, not sleep for a hopeful amount of time.

Build a simple sequential reference search over the source fixture. Compare all matching IDs on small uncapped queries and compare ordered top-K on capped queries. Use deterministic randomized datasets to check pruning has zero false negatives. A disabled-pruning scan is both a correctness comparison and a performance baseline.

## NL search test cases

NL search testing validates the frontend translation layer, not the LLM's intelligence. Use a mock LLM response in automated tests; live LLM calls are for manual demo validation only.

| Case | Input | Expected behavior |
| --- | --- | --- |
| Valid translation | "payment-service errors last 2 hours env=prod" | Hook returns valid JSON; preview shows correct fields; search API called and returns results |
| Missing required field | Mock LLM returns JSON without `tenant_id` | Validation rejects; fallback message shown; structured form offered |
| Invalid timestamp | Mock LLM returns `from > to` | Validation rejects; fallback message shown |
| Non-JSON response | Mock LLM returns plain text or markdown-fenced JSON | Parse fails; fallback message shown |
| Timeout | Mock LLM takes >8 seconds | AbortController fires; timeout message shown |
| Network error | Mock fetch rejects | Network error message shown |
| Search API 400 | Valid LLM JSON but API rejects (e.g., unknown tenant) | Show API error; pre-fill structured form with parseable fields |
| No API key configured | `NEXT_PUBLIC_ANTHROPIC_API_KEY` is unset | NL tab hidden; structured form is the only mode |
| User edits preview | Valid translation, user modifies a field before executing | Modified query sent to search API, not the original LLM output |

## Workload and run protocol

1. Save a deterministic generator, fixed seed, generator version, dataset checksum, bytes, record count, average/p95 record size, tenant distribution, tag cardinality, event-time span, and inserted match positions.
2. Use repetitive application logs, mixed structured envelopes with opaque messages, and low-compressibility messages. Do not use real secrets or personal incident data.
3. Include a small correctness fixture and a throughput corpus large enough to exercise sustained writes for at least 60 seconds. Record final size instead of assuming a tiny in-memory run represents disk behavior.
4. Define selective tenant/time/tag queries, broad time scans, absent text, common text, and rare text. Save the query list before measurement. Compute pruning rate as `1 - candidate_blocks / eligible_catalog_blocks`; state the denominator and any unavailable files. Mark an empty denominator N/A.
5. Measure search-only and mixed ingest/search runs separately. Label warm-cache runs. Only label a run cold-cache when a documented OS-specific procedure establishes that condition; a JVM restart alone does not establish it.
6. For JMH, start with two forks, three 1-second warmup iterations, and five 1-second measurement iterations. Record all actual parameters and increase duration if unstable. Consume results; keep generation and setup outside timed work. Measure codec, compression/decompression, metadata filtering, and scan hot paths.
7. For end-to-end search, warm up, then collect at least 1,000 requests per named scenario and three repeated runs. Preserve per-run results rather than selecting the best run. State client load model and scheduling so queueing and coordinated-omission risks are visible.
8. Record CPU, cores, RAM, disk model/type, filesystem, OS, JDK/vendor, JVM flags, dependency versions, source commit, configuration, exact commands, background load, and Docker/container limits if used.
9. Verify persisted record count/checksum after restart. Track admission failures and drain time. Performance results are invalid if correctness checks fail.

Compare a sequential scan with bounded parallel scan and pruning enabled/disabled on identical data. A platform-thread executor may be a benchmark-only comparison to test the virtual-thread hypothesis. Do not expand the product to support multiple engines. Do not claim superiority over another log database without a reproducible comparable experiment.

## Evidence bundle

Create one `docs/public/evidence/<run-id>/` per accepted measurement bundle. Include `environment.md`, `commands.txt`, `dataset-manifest.json`, `config.json`, `jmh.json`, `search-samples.csv`, `memory-samples.csv`, test summary, and a concise result interpretation. Save full local diagnostic logs under ignored `artifacts/`; publish only reviewed, sanitized evidence. Large generated datasets belong outside Git.

Until these harnesses exist, there are no valid project benchmark commands. Day 1 must establish `mvnw.cmd verify` from `backend`; Day 2 must document the actual JMH profile and invocation. Never paste an assumed benchmark command into a "passed" table.

## Merge and release gate

- Implementation work happens on `test`; validate the current commit and do not merge failing checks to `main`.
- Gate on relevant JUnit/integration tests plus compile/type checks and frontend production build once the frontend exists.
- Run relevant JMH checks when hot paths change. Use a short CI smoke run to prove benchmarks execute, not to assert noisy absolute performance thresholds.
- Save full baseline/final measurements on the same recorded machine. Establish numeric performance targets from the first valid baseline before tuning; do not retroactively invent a passing target.
- Record actual command, exit code, test counts, failures/skips, artifact paths, and remaining limitations. Documentation-only changes require link/content checks, not invented module benchmarks.

## Run ledger

| Date / commit | Scope | Command | Outcome | Evidence |
| --- | --- | --- | --- | --- |
| 2026-09-12 / no repository initialized | Initial structure audit | Filesystem, placeholder, and PATH inspection | Skeleton only; no runnable build | No runtime evidence |
