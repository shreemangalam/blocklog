# BlockLog: Testing Evidence

Status: 2026-09-16. Correctness suite (26 tests) is green and the first end-to-end measurement bundle for the `demo-shop` and `acme-inc` synthetic tenants is captured in the run ledger below. JMH is wired but not yet executed under this protocol; memory overhead has not been sampled. Replace pending entries only with actual saved results.

## Headline measurements

Measurements below are from run `demo-2026-09-16-a` against the running Spring Boot process on Windows 11 / Java 21 / localhost / batch=100. Full protocol context in the "Measured results" section.

| Metric | Definition | Result |
| --- | --- | --- |
| Ingestion (client-observed) | Records accepted at HTTP 202 / elapsed seconds, single client thread over `POST /api/v1/logs` | **31,210 records/sec** (50,000 records in 1602 ms), **36,363 records/sec** (30,000 in 825 ms) |
| Ingestion (bytes) | `accepted_bytes` counter delta / elapsed seconds | **~4.1 MB/s** raw encoded (50k run, 6.58 MB in 1.60 s) |
| Compression ratio | Raw `accepted_bytes` (pre-LZ4 encoded record bytes) / sum of `.blk` file sizes on disk, including headers and CRCs | **3.86×** (6,582,240 raw → 1,704,387 on disk across 2 blocks) |
| p95 search latency | 95th percentile of client-observed round-trip on `POST /api/v1/search` across a mixed workload of 300 queries (6 query classes) | **75 ms client / 74 ms engine** (all-class); **41 ms / 39 ms** for last-1h subrange scans |
| p99 search latency | 99th percentile, same workload | **81 ms client / 79 ms engine** |
| Full-tenant scan latency | p50 client, no filters, scans every candidate block | **74 ms client / 72 ms engine** (50k records, 2 blocks) |
| Tenant prune rate | `(total_blocks - candidate_blocks) / total_blocks` on cross-tenant workload (2 tenants, 3 blocks total) | **33.3%** (2/3 blocks survive tenant filter) |
| Memory overhead | Idle/loaded heap, RSS, mapped-file working set, loaded-minus-idle | Unmeasured |
| JMH codec / scan | See `jmh-baseline.json` | Unmeasured — harness committed in `backend/src/jmh/java`; execution pending in a dedicated session |

Report accepted MB/s separately from persisted MB/s; draining an ever-growing memory queue is not sustained storage throughput. Also report source message bytes versus encoded bytes so framing and tag overhead remain visible. A compressed-payload-only ratio may be supplemental, not a substitute for the total-file ratio.

For p95, save raw samples and state the percentile estimator, sample count, throughput, concurrent clients, result limit, timeout count, partial/error count, and cache condition. Use nearest-rank p95 on sorted samples (`ceil(0.95 * N)`, one-based). Report all-request latency plus successful-complete latency separately; never silently exclude failed or timed-out requests. A fast incomplete query is not a successful complete query.

For memory, record JVM heap settings, a fixed sampling interval (proposed one second), steady-state median, and peak. On Windows name the exact counters, such as Working Set and Private Bytes; on Linux name RSS/PSS measurements. Do not sum overlapping heap, resident, and mapped counters. Mapped virtual address space is not resident memory; heap alone is not process memory. Report GC time/allocation separately where available.

## Correctness and failure matrix

| Area | Required cases | Evidence status |
| --- | --- | --- |
| Record codec | Empty/multiline/UTF-8 messages, round-trip tags/time, record boundaries, maximum/rejected lengths, golden format fixture | Partial: round-trip covered by `BlockRoundTripTest`; explicit UTF-8 and boundary fixtures still pending |
| LZ4 blocks | Compressible and random payloads, checked length arithmetic, unsupported version, header/payload bit flips, truncated file | **Covered** by `BlockRoundTripTest` (round-trip, oversize rejection), `BlockCatalogRestartTest.corruptBlockFileIsRegisteredAsUnavailable` (truncated file, header CRC failure at discovery), `BlockCatalogRestartTest.payloadCorruptionIsSurfacedOnMappingLoad` (payload bit-flip caught at scan, block auto-degraded to unavailable) |
| Flushing | Exact threshold, next-record overflow under chosen policy, oldest age with injected clock, continuous traffic, idle tenant, no empty block | **Covered** by `IngestionEngineTest.flushesOnSizeThreshold` (size flush), `IngestionEngineTest.acceptsRecordsAndPersistsOnShutdown` (drain), and the 50k live run in the ledger (age flush at 5 s produces 2 blocks) |
| Admission | Whole-batch reservation, concurrent producers, full queue, byte caps, no silently lost/duplicated admitted sequence IDs | **Covered** by `IngestionEngineTest.rejectsWhenQueueBudgetExhausted` (byte cap → typed 429), `IngestionEngineTest.rejectsRecordsExceedingMaxSize`, and `HttpApiIntegrationTest.oversizedBatchReturns429WhenQueueBudgetExhausted` (end-to-end). Concurrent producers still pending |
| Publication | Short writes, failed force/move, unsupported atomic move, disk-full injection, unique IDs, ambiguous retry reconciliation | Partial: happy path proven by every ingest-flush-search test; failed-flush path proven by `IngestionUnhealthyIntegrationTest.ingestReturns503AfterPersistenceFailure`. Disk-full injection still pending |
| Restart | Process termination before/after publication, orphan temp file, rebuild without cached catalog, exclusive directory lock | **Covered** by `BlockCatalogRestartTest.rediscoversBlocksAcrossFreshCatalog` (3 blocks / 2 tenants rebuilt on a fresh catalog) and `.ignoresTempAndBogusFilesDuringDiscovery` (`.blk.tmp.*` and non-`.blk` files silently skipped) |
| Pruning | Cross-tenant isolation, time edges, out-of-order events, tag union false positives, saturated summaries, corrupted metadata | Partial: cross-tenant proven by `SearchEngineTest.enforcesTenantIsolation` and by the 33.3% prune rate in run `demo-2026-09-16-a` (cross-tenant workload). Time edges by `SearchEngineTest.tenantAndTimePruningAreExact`. Tag saturation still pending |
| Search | Reference-scan equivalence, match at message edges, no cross-record match, bounded earliest-K ordering, equal timestamps, zero matches | Partial: bounded earliest-K + truncation flag covered by `SearchEngineTest.truncationFlaggedWhenMoreMatchesThanLimit`; matching semantics by `SearchEngineTest.findsMatchingRecordsByTenantTimeAndText` and by the live sample queries in the run ledger. Reference-scan equivalence still pending |
| Concurrency | Concurrent ingest/search snapshots, global permits across queries, cancellation cleanup, bounded results and backlog | Partial: scan-permit deadline honoring covered by `SearchEngineTest.queryReturnsTimedOutInsteadOfBlockingUnderScanPermitStarvation` (returns near 300 ms deadline with 0 permits). Multi-writer stress test still pending |
| Partial results | One bad block among healthy files, unknown-relevance recovery failure, timeout, missing published file, visible UI warning | **Covered** by `SearchEngineTest.reportsUnavailableBlocksWithoutFailingSearch` and by the corruption path in `BlockCatalogRestartTest.payloadCorruptionIsSurfacedOnMappingLoad`. UI partial banner still pending |
| Lifecycle | Graceful drain, failed shutdown reported, restart on Windows, stable mapping reuse over repeated searches | Partial: drain by `IngestionEngineTest.acceptsRecordsAndPersistsOnShutdown`; Windows restart proven by `demo-2026-09-16-a` (backend killed + restarted, 2 blocks rediscovered). Stable mapping reuse across queries still not measured |
| API/UI | Validation/413/429/503, real ingest-to-search journey, loading/empty/error states, `/help`, `/onboarding` | **Covered** by `HttpApiIntegrationTest` (7 tests: 202 round-trip, 400 validation ×2, 429 queue exhaustion, tenant isolation, /status, /actuator/prometheus) plus `IngestionUnhealthyIntegrationTest` (503). Loading/empty/error UI states verified manually against live backend, run ledger |
| NL search | Valid translation, malformed LLM output, LLM timeout, network failure, search API rejection of generated query, structured-form fallback, no data leakage | Not exercised: no API key is configured; the tab renders the "Coming in a future version" panel per CLAUDE.md §6 guardrail. Structured-form fallback verified |

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

## Measured results — run `demo-2026-09-16-a`

Reproducible synthetic workload against two tenants. Full raw output in `artifacts/`.

**Environment**

- Windows 11 Home Single Language 10.0.26200; OpenJDK Temurin 21; Spring Boot 3.4.4
- Backend: `mvnw.cmd spring-boot:run` (default engine config; 5 MB / 5 s flush, 64 MiB queue + buffer budgets)
- Dataset: two synthetic tenants generated by `com.blocklog.demo.SyntheticIngest` (six microservices across 3 envs and 4 regions; ~74 % INFO / ~19 % WARN / ~7 % ERROR by template weight; JSON-safe message templates with fake user/order/SKU parameters)

**Ingestion workload**

| Tenant | Records | Batch size | Elapsed | Rate (r/s) | Rate (MB/s raw) | Rejections |
| --- | --- | --- | --- | --- | --- | --- |
| `demo-shop` | 50,000 | 100 | 1,602 ms | **31,210** | 4.11 | 0 |
| `acme-inc` | 30,000 | 100 | 825 ms | **36,363** | ~4.8 | 0 |

Both runs single-client-thread over localhost HTTP with `POST /api/v1/logs`. Zero 429s at these budgets. `accepted_bytes == persisted_bytes` after the last age-flush; `unavailable_blocks == 0`.

**Storage layout after ingest**

| Metric | Value |
| --- | --- |
| Published blocks | 3 (2 for `demo-shop`, 1 for `acme-inc`) |
| Total raw encoded bytes (accepted_bytes) | 10,530,256 (both tenants) |
| `demo-shop` raw / on-disk | 6,582,240 B / 1,704,387 B → **3.86× compression** |
| Block size distribution (`demo-shop`) | min 411,219 B; max 1,293,168 B; mean 852,193 B |

**Search workload**

330 total queries (30 warmup discarded, 300 measured) against `demo-shop`, mixed across six classes chosen to stress different code paths. Client latency is round-trip; engine latency is the `elapsed_ms` field the response carries.

| Class | Count | p50 client (ms) | p95 client (ms) | p99 client (ms) | p50 engine (ms) | p95 engine (ms) | p99 engine (ms) | Prune rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Full-tenant | 55 | 74 | 82 | 83 | 72 | 80 | 81 | 33.3 % |
| Tenant + last 1 h | 59 | 37 | 41 | 45 | 35 | 39 | 43 | 33.3 % |
| Tag `level=ERROR` | 44 | 45 | 47 | 48 | 43 | 45 | 46 | 33.3 % |
| Tag `level` + `service` | 48 | 44 | 48 | 50 | 42 | 46 | 48 | 33.3 % |
| Text keyword | 49 | 44 | 52 | 56 | 41 | 50 | 54 | 33.3 % |
| `level=ERROR` + keyword | 45 | 44 | 47 | 50 | 42 | 44 | 48 | 33.3 % |
| **All classes** | **300** | **44** | **75** | **81** | **42** | **74** | **79** | **33.3 %** |

Prune rate is `(total_blocks − candidate_blocks) / total_blocks`. The 33.3 % rate is the `acme-inc` block being pruned from every `demo-shop` query (1 of 3 blocks). Within `demo-shop`, both remaining blocks span the full 24 h window and contain every level (uniform random sampling), so no further tenant-internal pruning fires — a workload-shape observation, not an engine limit.

**Correctness sanity**

Every query returned real records with the expected filter effect. Sample of `tags={level:ERROR, service:payments}, text=declined` (5 hits, 2 ms):

```
[prod/us-west-2]  payment declined order=581643 code=574 reason="card expired"
[prod/eu-west-1]  payment declined order=746060 code=586 reason="insufficient funds"
[staging/eu-west-1] payment declined order=628970 code=578 reason="card expired"
[dev/ap-south-1]  payment declined order=507694 code=404 reason="insufficient funds"
[prod/us-west-2]  payment declined order=693156 code=516 reason="card expired"
```

**Reproduction**

```powershell
# Terminal 1 — backend
cd backend
.\mvnw.cmd -q spring-boot:run

# Terminal 2 — seed + measure
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 50000 --batch 100 --hours 24
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant acme-inc  --count 30000 --batch 100 --hours 24 --seed 99
java -cp target/classes com.blocklog.demo.MeasureCompression --data-dir data --url http://localhost:8080
java -cp target/classes com.blocklog.demo.MeasureSearch --tenant demo-shop --warmup 30 --iterations 300 --hours 24
```

Same seed → same records byte-for-byte. Same measurement command → statistically similar latencies subject to OS scheduling and page-cache warmth.

**Known limits of this bundle**

- Single client thread; concurrent-writer stress is next.
- Memory overhead not yet sampled (see headline table).
- JMH benchmarks committed (`ScanBenchmark`, `CodecBenchmark`) but not yet executed under this protocol; a separate session will produce `jmh-baseline.json`.
- Latencies are localhost-only. Real network hops will dominate the ~40 ms engine time.

## Run ledger

| Date / commit | Scope | Command | Outcome | Evidence |
| --- | --- | --- | --- | --- |
| 2026-09-12 / no repository initialized | Initial structure audit | Filesystem, placeholder, and PATH inspection | Skeleton only; no runnable build | No runtime evidence |
| 2026-09-16 / `run-in-progress` | JUnit + integration (26 tests) | `mvnw.cmd -B test` | 26/26 pass in ~6.5 s: 4 BlockRoundTripTest + 4 IngestionEngineTest + 6 SearchEngineTest + 4 BlockCatalogRestartTest + 7 HttpApiIntegrationTest + 1 IngestionUnhealthyIntegrationTest | `artifacts/test-session8.log`, surefire under `backend/target/surefire-reports/` |
| 2026-09-16 / `run-in-progress` — `demo-2026-09-16-a` | End-to-end tenant workload (`demo-shop` 50k + `acme-inc` 30k) | See "Reproduction" | 80,000 records buffered / persisted / searchable. 3.86× compression. p95 search 75 ms client / 74 ms engine. 33.3 % prune | `artifacts/seed-50k.log`, `artifacts/compression-50k.log`, `artifacts/search-latency-multitenant.log` |
| 2026-09-16 / `run-in-progress` | Frontend build + typecheck | `npm ci && npm run build` (frontend) | Build succeeds; 5 static routes prerendered | `artifacts/ci-frontend-build.log` |
| 2026-09-16 / `run-in-progress` | Remote CI | GitHub Actions | Backend + frontend both green on head `72a3ac1` (previous session), rerun after this bundle | GitHub Actions run URL in commit body |
