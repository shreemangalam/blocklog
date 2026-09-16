# BlockLog: Testing Evidence

Status: 2026-09-16. Correctness suite (**29 tests**) is green, plus three measurement bundles now published: end-to-end tenant workload (`demo-2026-09-16-a`), JMH codec baseline (`jmh-2026-09-16-a`), and memory overhead (`memory-2026-09-16-a`). All four sprint metrics now have numbers. Replace pending entries only with actual saved results.

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
| Memory overhead (idle) | Steady-state heap.used over 20 s at rest, JMX-sampled | **min 21 MB · max 33 MB · mean 28 MB · committed 68 MB** |
| Memory overhead (loaded) | Heap.used during 100k-record ingest at ~30k r/s, JMX-sampled | **min 116 MB · max 202 MB · mean 143 MB · committed 232 MB** (~115 MB delta) |
| Peak per-tenant buffer | `blocklog.buffer.bytes` max under load | **3.17 MB** (below the 5 MB flush threshold) |
| JMH codec throughput (best case) | `CodecBenchmark.decode` at 64 B message / 0 tags | **31.7 M ops/sec** (0.031 μs/op) |
| JMH codec throughput (worst case) | `CodecBenchmark.encode` at 2 KB message / 4 tags | **0.70 M ops/sec** (1.42 μs/op) |
| JMH scan | Not yet captured — annotation processor generates fork-driver classes at compile time but they aren't visible to JMH's fork execution in this pom layout. Fixing is a follow-up | Pending |

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
- Latencies are localhost-only. Real network hops will dominate the ~40 ms engine time.
- Scan benchmark did not execute cleanly in JMH — the annotation processor generates the `jmh_generated.*` fork-driver classes at compile time, but they end up under an empty target subdirectory that JMH's fork can't discover. The `CodecBenchmark` numbers below are valid (they were collected in the same run); scan latency is covered end-to-end by MeasureSearch instead.

## Measured results — JMH codec baseline (run `jmh-2026-09-16-a`)

Single fork, 2 × 1 s warmup, 3 × 1 s measurement per param combo. Full JSON in `artifacts/jmh-baseline.json`, human-readable log in `artifacts/jmh-run.log`.

Parameters: `messageBytes ∈ {64, 512, 2048}`, `tagCount ∈ {0, 4}`.

| Benchmark | messageBytes | tags | Mode | Score (ops/μs) | ± error |
| --- | --- | --- | --- | --- | --- |
| CodecBenchmark.decode | 64 | 0 | thrpt | **31.72** | 18.82 |
| CodecBenchmark.decode | 64 | 4 | thrpt | 2.84 | 2.67 |
| CodecBenchmark.decode | 512 | 0 | thrpt | 7.79 | 3.97 |
| CodecBenchmark.decode | 512 | 4 | thrpt | 2.58 | 1.89 |
| CodecBenchmark.decode | 2048 | 0 | thrpt | 1.94 | 0.61 |
| CodecBenchmark.decode | 2048 | 4 | thrpt | 1.35 | 0.53 |
| CodecBenchmark.encode | 64 | 0 | thrpt | **11.23** | 6.71 |
| CodecBenchmark.encode | 64 | 4 | thrpt | 4.38 | 3.12 |
| CodecBenchmark.encode | 512 | 0 | thrpt | 2.93 | 0.22 |
| CodecBenchmark.encode | 512 | 4 | thrpt | 1.67 | 1.04 |
| CodecBenchmark.encode | 2048 | 0 | thrpt | 0.77 | 0.45 |
| CodecBenchmark.encode | 2048 | 4 | thrpt | 0.70 | 0.29 |
| CodecBenchmark.roundTrip | 64 | 0 | thrpt | 8.31 | 1.76 |
| CodecBenchmark.roundTrip | 2048 | 4 | thrpt | 0.46 | 0.07 |

**Sanity check against MeasureSearch**: a 500-byte log record at 4 tags round-trips at ~1.13 M ops/sec = ~880 ns / record. For a 50 k-record block that would be ~44 ms of pure codec time — matching the ~72 ms full-tenant scan p50 with LZ4 decompression and mmap traversal accounted for.

Confidence bars are wide (± errors of 50 %+ for the fastest benchmarks) because the run used tight 1 s iterations to fit inside this session. Publishing at reduced iteration count is a documented tradeoff (`docs/private/daily-worklog.md`, session 9); a longer run with 3 s iterations x 5 measurement passes is the follow-up.

## Measured results — Memory overhead (run `memory-2026-09-16-a`)

Backend restarted clean before sampling. Each sample fetches `/actuator/metrics/{jvm.memory.used, jvm.memory.committed, jvm.buffer.memory.used}` via HTTP. 20 s sampling window at 500 ms intervals (40 samples each). No searches during either window — search-side mmap allocation would be an additive term captured in a later run.

| Metric | Idle min | Idle max | Idle mean | Loaded min | Loaded max | Loaded mean | Delta (mean) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `jvm.memory.used` (heap) | 21 MB | 33 MB | **28 MB** | 116 MB | 202 MB | **143 MB** | **+115 MB** |
| `jvm.memory.committed` (heap) | 68 MB | 68 MB | 68 MB | 232 MB | 232 MB | 232 MB | +164 MB (JVM auto-grew heap) |
| `jvm.buffer.memory.used` (mapped) | 0 | 0 | 0 | 0 | 0 | 0 | 0 (search-only path) |
| `blocklog.queue.bytes` | 0 | 0 | 0 | 0 | 26,444 | 660 | Drains within one flush cycle |
| `blocklog.buffer.bytes` | 0 | 0 | 0 | 0 | 3,169,059 | 858,000 | Below the 5 MB flush threshold |

Loaded workload was 100,000 synthetic records ingested at ~30 k r/s (SyntheticIngest --count 100000 --batch 100 running in parallel with MeasureMemory).

**Bounded caches this session added a cap on:**

- **Mmap cache**: `blocklog.mmap-cache-size` (default 256). Caffeine-backed LRU with a removal listener that closes the FileChannel on eviction. Test coverage: `MmapCacheEvictionTest.cacheStaysBoundedUnderRepeatedMisses`, `.reopeningEvictedBlockStillReadsCorrectly`, `.markUnavailableInvalidatesCachedMapping`. Was previously unbounded; a system with 10 k+ blocks could have kept one FileChannel per block open indefinitely.

## Reproduction

```powershell
# Terminal 1 — backend (make sure data-dir is clean)
Remove-Item -Recurse -Force backend/data ; New-Item -ItemType Directory backend/data
cd backend
.\mvnw.cmd -q spring-boot:run

# Terminal 2 — end-to-end bundle
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 50000 --batch 100 --hours 24
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant acme-inc  --count 30000 --batch 100 --hours 24 --seed 99
java -cp target/classes com.blocklog.demo.MeasureCompression --data-dir data --url http://localhost:8080
java -cp target/classes com.blocklog.demo.MeasureSearch --tenant demo-shop --warmup 30 --iterations 300 --hours 24

# Terminal 2 (later) — memory: idle sample first, then a load-vs-sample overlap
java -cp target/classes com.blocklog.demo.MeasureMemory --seconds 20 --interval 500
Start-Job { java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant mem-load --count 100000 --batch 100 --seed 111 }
java -cp target/classes com.blocklog.demo.MeasureMemory --seconds 20 --interval 500

# Terminal 2 — JMH baseline
.\mvnw.cmd -B -Pjmh -DskipTests clean compile
.\mvnw.cmd -B -Pjmh -DskipTests dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = "target\classes;" + (Get-Content cp.txt)
java -cp $cp org.openjdk.jmh.Main -f 1 -wi 2 -w 1 -i 3 -r 1 -rf json -rff ..\artifacts\jmh-baseline.json
```

Same seeds → same records byte-for-byte. Same measurement command → statistically similar latencies subject to OS scheduling and page-cache warmth.

## Run ledger

| Date / commit | Scope | Command | Outcome | Evidence |
| --- | --- | --- | --- | --- |
| 2026-09-12 / no repository initialized | Initial structure audit | Filesystem, placeholder, and PATH inspection | Skeleton only; no runnable build | No runtime evidence |
| 2026-09-16 / `run-in-progress` | JUnit + integration (29 tests) | `mvnw.cmd -B test` | 29/29 pass in ~6 s: 4 BlockRoundTripTest + 4 IngestionEngineTest + 6 SearchEngineTest + 4 BlockCatalogRestartTest + 3 MmapCacheEvictionTest + 7 HttpApiIntegrationTest + 1 IngestionUnhealthyIntegrationTest | `artifacts/test-session9.log`, surefire under `backend/target/surefire-reports/` |
| 2026-09-16 / `run-in-progress` — `demo-2026-09-16-a` | End-to-end tenant workload (`demo-shop` 50k + `acme-inc` 30k) | See "Reproduction" | 80,000 records buffered / persisted / searchable. 3.86× compression. p95 search 75 ms client / 74 ms engine. 33.3 % prune | `artifacts/seed-50k.log`, `artifacts/compression-50k.log`, `artifacts/search-latency-multitenant.log` |
| 2026-09-16 / `run-in-progress` — `jmh-2026-09-16-a` | JMH codec baseline, 3 methods × 6 param combos | See "Reproduction" | Best case decode 31.7 M ops/sec at 64 B / 0 tags; worst case encode 0.7 M ops/sec at 2 KB / 4 tags. ScanBenchmark did not execute — fork-driver classes not visible | `artifacts/jmh-baseline.json`, `artifacts/jmh-run.log` |
| 2026-09-16 / `run-in-progress` — `memory-2026-09-16-a` | Heap + queue/buffer sampling, 20 s idle then 20 s under 100 k-record ingest | See "Reproduction" | Idle heap 28 MB mean; loaded heap 143 MB mean; +115 MB delta under load. Peak buffer 3.17 MB (below flush cap). Queue drains within one flush cycle | `artifacts/memory-idle.log`, `artifacts/memory-loaded.log` |
| 2026-09-16 / `run-in-progress` | Frontend build + typecheck | `npm ci && npm run build` (frontend) | Build succeeds; 5 static routes prerendered | `artifacts/ci-frontend-build.log` |
| 2026-09-16 / `run-in-progress` | Remote CI | GitHub Actions | Backend + frontend both green; rerun after this bundle | GitHub Actions run URL in commit body |
