# BlockLog: Testing Evidence

Correctness suite: **42 tests** across 10 test classes, all passing. Three measurement bundles published: end-to-end tenant workload (`demo-2026-09-16-a`), JMH codec baseline (`jmh-2026-09-16-a`), and memory overhead (`memory-2026-09-16-a`). Raw diagnostic logs are in the gitignored `artifacts/` directory; to reproduce the data, run the commands in the Reproduction sections below.

## Headline measurements

Measurements below are from run `demo-2026-09-16-a` against the running Spring Boot process on Windows 11 / Java 21 / localhost / batch=100. Full protocol context in the "Measured results" section.

| Metric | Definition | Result |
| --- | --- | --- |
| Ingestion (client-observed) | Records accepted at HTTP 202 / elapsed seconds, single client thread over `POST /api/v1/logs` | **31,210 records/sec** (50,000 records in 1602 ms), **36,363 records/sec** (30,000 in 825 ms) |
| Ingestion (bytes) | `accepted_bytes` counter delta / elapsed seconds | **~4.1 MB/s** raw encoded (50k run, 6.58 MB in 1.60 s) |
| Compression ratio | Raw `accepted_bytes` (pre-LZ4 encoded record bytes) / sum of `.blk` file sizes on disk, including headers and CRCs | **3.86x** (6,582,240 raw to 1,704,387 on disk across 2 blocks) |
| p95 search latency | 95th percentile of client-observed round-trip on `POST /api/v1/search` across a mixed workload of 300 queries (6 query classes) | **75 ms client / 74 ms engine** (all-class); **41 ms / 39 ms** for last-1h subrange scans |
| p99 search latency | 99th percentile, same workload | **81 ms client / 79 ms engine** |
| Full-tenant scan latency | p50 client, no filters, scans every candidate block | **74 ms client / 72 ms engine** (50k records, 2 blocks) |
| Tenant prune rate | `(total_blocks - candidate_blocks) / total_blocks` on cross-tenant workload (2 tenants, 3 blocks total) | **33.3%** (2/3 blocks survive tenant filter) |
| Memory overhead (idle) | Steady-state heap.used over 20 s at rest, JMX-sampled | **min 21 MB, max 33 MB, mean 28 MB, committed 68 MB** |
| Memory overhead (loaded) | Heap.used during 100k-record ingest at ~30k r/s, JMX-sampled | **min 116 MB, max 202 MB, mean 143 MB, committed 232 MB** (~115 MB delta) |
| Peak per-tenant buffer | `blocklog.buffer.bytes` max under load | **3.17 MB** (below the 5 MB flush threshold) |
| JMH codec throughput (best case) | `CodecBenchmark.decode` at 64 B message / 0 tags | **31.7M decode ops/sec** (0.031 us/op) |
| JMH codec throughput (worst case) | `CodecBenchmark.encode` at 2 KB message / 4 tags | **0.70M ops/sec** (1.42 us/op) |
| JMH scan (small) | `ScanBenchmark.scanFullRangeText`, 8 blocks x 250 records = 2,000 records, keyword filter | **0.45 ms avgt / 0.53 ms sample-p50 / 0.68 ms p95** |
| JMH scan (medium) | 8 x 1000 = 8,000 records, keyword filter | **1.82 ms avgt / 2.13 ms sample-p50** |
| JMH scan (large text-only) | 32 x 1000 = 32,000 records, keyword filter | **6.62 ms avgt / 7.05 ms sample-p50** |
| JMH scan (large, tag + text) | 32 x 1000 = 32,000 records, `env=prod` tag pre-check + text | **3.88 ms avgt** (tag pre-check halves work vs. text-only) |

## Correctness and failure matrix

| Area | Required cases | Evidence |
| --- | --- | --- |
| Record codec | Empty/multiline/UTF-8 messages, round-trip tags/time, record boundaries, maximum/rejected lengths, golden format fixture | Partial: round-trip covered by `BlockRoundTripTest`; explicit UTF-8 and boundary fixtures still pending |
| LZ4 blocks | Compressible and random payloads, checked length arithmetic, unsupported version, header/payload bit flips, truncated file | **Covered** by `BlockRoundTripTest` (round-trip, oversize rejection), `BlockCatalogRestartTest.corruptBlockFileIsRegisteredAsUnavailable` (truncated file, header CRC failure at discovery), `BlockCatalogRestartTest.payloadCorruptionIsSurfacedOnMappingLoad` (payload bit-flip caught at scan, block auto-degraded to unavailable) |
| Flushing | Exact threshold, next-record overflow under chosen policy, oldest age with injected clock, continuous traffic, idle tenant, no empty block | **Covered** by `IngestionEngineTest.flushesOnSizeThreshold` (size flush), `IngestionEngineTest.acceptsRecordsAndPersistsOnShutdown` (drain), and the 50k live run (age flush at 5 s produces 2 blocks) |
| Admission | Whole-batch reservation, concurrent producers, full queue, byte caps, no silently lost/duplicated admitted sequence IDs | **Covered** by `IngestionEngineTest.rejectsWhenQueueBudgetExhausted` (byte cap, typed 429), `IngestionEngineTest.rejectsRecordsExceedingMaxSize`, and `HttpApiIntegrationTest.oversizedBatchReturns429WhenQueueBudgetExhausted` (end-to-end). Concurrent producers covered by `ConcurrentWriterStressTest` |
| Publication | Short writes, failed force/move, unsupported atomic move, disk-full injection, unique IDs, ambiguous retry reconciliation | Partial: happy path proven by every ingest-flush-search test; failed-flush path proven by `IngestionUnhealthyIntegrationTest.ingestReturns503AfterPersistenceFailure`. Disk-full injection still pending |
| Restart | Process termination before/after publication, orphan temp file, rebuild without cached catalog, exclusive directory lock | **Covered** by `BlockCatalogRestartTest.rediscoversBlocksAcrossFreshCatalog` (3 blocks / 2 tenants rebuilt on a fresh catalog) and `.ignoresTempAndBogusFilesDuringDiscovery` (`.blk.tmp.*` and non-`.blk` files silently skipped) |
| Pruning | Cross-tenant isolation, time edges, out-of-order events, tag union false positives, saturated summaries, corrupted metadata | Partial: cross-tenant proven by `SearchEngineTest.enforcesTenantIsolation` and by the 33.3% prune rate in run `demo-2026-09-16-a` (cross-tenant workload). Time edges by `SearchEngineTest.tenantAndTimePruningAreExact`. Tag saturation still pending |
| Search | Reference-scan equivalence, match at message edges, no cross-record match, bounded earliest-K ordering, equal timestamps, zero matches | Partial: bounded earliest-K + truncation flag covered by `SearchEngineTest.truncationFlaggedWhenMoreMatchesThanLimit`; matching semantics by `SearchEngineTest.findsMatchingRecordsByTenantTimeAndText` and by live sample queries. Reference-scan equivalence covered by `ReferenceScanEquivalenceTest` |
| Concurrency | Concurrent ingest/search snapshots, global permits across queries, cancellation cleanup, bounded results and backlog | Partial: scan-permit deadline honoring covered by `SearchEngineTest.queryReturnsTimedOutInsteadOfBlockingUnderScanPermitStarvation` (returns near 300 ms deadline with 0 permits). Multi-writer conservation by `ConcurrentWriterStressTest` (4000 records, 4 concurrent searchers) |
| Partial results | One bad block among healthy files, unknown-relevance recovery failure, timeout, missing published file, visible UI warning | **Covered** by `SearchEngineTest.reportsUnavailableBlocksWithoutFailingSearch` and by the corruption path in `BlockCatalogRestartTest.payloadCorruptionIsSurfacedOnMappingLoad` |
| Lifecycle | Graceful drain, failed shutdown reported, restart on Windows, stable mapping reuse over repeated searches | Partial: drain by `IngestionEngineTest.acceptsRecordsAndPersistsOnShutdown`; Windows restart proven by `demo-2026-09-16-a` (backend killed + restarted, 2 blocks rediscovered). Stable mapping reuse across queries still not measured |
| API/UI | Validation/413/429/503, real ingest-to-search journey, loading/empty/error states, `/help`, `/onboarding` | **Covered** by `HttpApiIntegrationTest` (9 tests: 202 round-trip, 400 validation x4 including oversized tag key/value, 429 queue exhaustion, tenant isolation, /status, /actuator/prometheus) plus `IngestionUnhealthyIntegrationTest` (503). UI states verified against live backend |
| NL search | Valid translation, malformed model output, timeout, network failure, search API rejection of generated query, structured-form fallback, no data leakage | Not exercised end-to-end: no API key configured; the tab renders the "Coming in a future version" panel. Translation call is proxied through a server-side Next.js route handler (`/api/nl-translate`), so the API key never reaches the browser. Structured-form fallback verified |

## NL search test cases

NL search testing validates the frontend translation layer, not the model's intelligence. Use a mock model response in automated tests; live calls are for manual demo validation only.

| Case | Input | Expected behavior |
| --- | --- | --- |
| Valid translation | "payment-service errors last 2 hours env=prod" | Hook returns valid JSON; preview shows correct fields; search API called and returns results |
| Missing required field | Mock response returns JSON without `tenant_id` | Validation rejects; fallback message shown; structured form offered |
| Invalid timestamp | Mock response returns `from > to` | Validation rejects; fallback message shown |
| Non-JSON response | Mock response returns plain text or markdown-fenced JSON | Parse fails; fallback message shown |
| Timeout | Mock response takes >8 seconds | AbortController fires; timeout message shown |
| Network error | Mock fetch rejects | Network error message shown |
| Search API 400 | Valid translation JSON but API rejects (e.g., unknown tenant) | Show API error; pre-fill structured form with parseable fields |
| No API key configured | `ANTHROPIC_API_KEY` is unset on the server | NL tab shows disabled state; structured form is the only mode |
| User edits preview | Valid translation, user modifies a field before executing | Modified query sent to search API, not the original model output |

## Methodology

All measurements use deterministic synthetic workloads with fixed random seeds for byte-for-byte reproducibility. Latency percentiles use nearest-rank estimation on sorted samples (`ceil(0.95 * N)`, one-based). Memory measurements sample JVM heap metrics at fixed intervals via JMX actuator endpoints.

Ingestion throughput reports client-observed MB/s separately from persisted MB/s; draining an in-memory queue is not sustained storage throughput. Compression ratio uses total file size (including headers and CRCs), not compressed payload alone. Search latency reports all-request and successful-complete distributions separately; timed-out or partial queries are never silently excluded.

Evidence bundles live under `artifacts/` (gitignored). Each bundle includes environment details, exact commands, dataset manifests, and raw output. Large generated datasets belong outside Git; the reproduction commands below regenerate them deterministically.

## Measured results: run `demo-2026-09-16-a`

Reproducible synthetic workload against two tenants. Full raw output in `artifacts/`.

**Environment**

- Windows 11 Home Single Language 10.0.26200; OpenJDK Temurin 21; Spring Boot 3.4.4
- Backend: `mvnw.cmd spring-boot:run` (default engine config; 5 MB / 5 s flush, 64 MiB queue + buffer budgets)
- Dataset: two synthetic tenants generated by `com.blocklog.demo.SyntheticIngest` (six microservices across 3 envs and 4 regions; ~74% INFO / ~19% WARN / ~7% ERROR by template weight; JSON-safe message templates with fake user/order/SKU parameters)

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
| `demo-shop` raw / on-disk | 6,582,240 B / 1,704,387 B -> **3.86x compression** |
| Block size distribution (`demo-shop`) | min 411,219 B; max 1,293,168 B; mean 852,193 B |

**Search workload**

330 total queries (30 warmup discarded, 300 measured) against `demo-shop`, mixed across six classes chosen to stress different code paths. Client latency is round-trip; engine latency is the `elapsed_ms` field the response carries.

| Class | Count | p50 client (ms) | p95 client (ms) | p99 client (ms) | p50 engine (ms) | p95 engine (ms) | p99 engine (ms) | Prune rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Full-tenant | 55 | 74 | 82 | 83 | 72 | 80 | 81 | 33.3% |
| Tenant + last 1 h | 59 | 37 | 41 | 45 | 35 | 39 | 43 | 33.3% |
| Tag `level=ERROR` | 44 | 45 | 47 | 48 | 43 | 45 | 46 | 33.3% |
| Tag `level` + `service` | 48 | 44 | 48 | 50 | 42 | 46 | 48 | 33.3% |
| Text keyword | 49 | 44 | 52 | 56 | 41 | 50 | 54 | 33.3% |
| `level=ERROR` + keyword | 45 | 44 | 47 | 50 | 42 | 44 | 48 | 33.3% |
| **All classes** | **300** | **44** | **75** | **81** | **42** | **74** | **79** | **33.3%** |

Prune rate is `(total_blocks - candidate_blocks) / total_blocks`. The 33.3% rate is the `acme-inc` block being pruned from every `demo-shop` query (1 of 3 blocks). Within `demo-shop`, both remaining blocks span the full 24 h window and contain every level (uniform random sampling), so no further tenant-internal pruning fires. This is a workload-shape observation, not an engine limit.

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
# Terminal 1: backend
cd backend
.\mvnw.cmd -q spring-boot:run

# Terminal 2: seed + measure
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 50000 --batch 100 --hours 24
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant acme-inc  --count 30000 --batch 100 --hours 24 --seed 99
java -cp target/classes com.blocklog.demo.MeasureCompression --data-dir data --url http://localhost:8080
java -cp target/classes com.blocklog.demo.MeasureSearch --tenant demo-shop --warmup 30 --iterations 300 --hours 24
```

Same seed produces same records byte-for-byte. Same measurement command produces statistically similar latencies subject to OS scheduling and page-cache warmth.

**Known limits of this bundle**

- Single client thread; concurrent-writer stress is a separate test (`ConcurrentWriterStressTest`).
- Latencies are localhost-only. Real network hops will dominate the ~40 ms engine time.
- The last JMH ScanBenchmark param combo (32 x 1000, sample mode) fails to load its `_jmhTest` class on this JVM; earlier param combos produce valid numbers.

## Measured results: JMH codec baseline (run `jmh-2026-09-16-a`)

Single fork, 2 x 1 s warmup, 3 x 1 s measurement per param combo. Full JSON in `artifacts/jmh-baseline.json`, human-readable log in `artifacts/jmh-run.log`.

Parameters: `messageBytes` in {64, 512, 2048}, `tagCount` in {0, 4}.

| Benchmark | messageBytes | tags | Mode | Score (ops/us) | +/- error |
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

**Sanity check against MeasureSearch**: a 500-byte log record at 4 tags round-trips at ~1.13M ops/sec = ~880 ns / record. For a 50k-record block that would be ~44 ms of pure codec time, matching the ~72 ms full-tenant scan p50 with LZ4 decompression and mmap traversal accounted for.

Confidence bars are wide (errors of 50%+ for the fastest benchmarks) because the run used tight 1 s iterations for time efficiency. A longer run with 3 s iterations x 5 measurement passes would narrow the error bars.

## Measured results: Memory overhead (run `memory-2026-09-16-a`)

Backend restarted clean before sampling. Each sample fetches `/actuator/metrics/{jvm.memory.used, jvm.memory.committed, jvm.buffer.memory.used}` via HTTP. 20 s sampling window at 500 ms intervals (40 samples each). No searches during either window; search-side mmap allocation would be an additive term captured in a later run.

| Metric | Idle min | Idle max | Idle mean | Loaded min | Loaded max | Loaded mean | Delta (mean) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `jvm.memory.used` (heap) | 21 MB | 33 MB | **28 MB** | 116 MB | 202 MB | **143 MB** | **+115 MB** |
| `jvm.memory.committed` (heap) | 68 MB | 68 MB | 68 MB | 232 MB | 232 MB | 232 MB | +164 MB (JVM auto-grew heap) |
| `jvm.buffer.memory.used` (mapped) | 0 | 0 | 0 | 0 | 0 | 0 | 0 (search-only path) |
| `blocklog.queue.bytes` | 0 | 0 | 0 | 0 | 26,444 | 660 | Drains within one flush cycle |
| `blocklog.buffer.bytes` | 0 | 0 | 0 | 0 | 3,169,059 | 858,000 | Below the 5 MB flush threshold |

Loaded workload was 100,000 synthetic records ingested at ~30k r/s (SyntheticIngest --count 100000 --batch 100 running in parallel with MeasureMemory).

**Bounded caches:**

- **Mmap cache**: `blocklog.mmap-cache-size` (default 256). Caffeine-backed LRU with a removal listener that closes the FileChannel on eviction. Test coverage: `MmapCacheEvictionTest.cacheStaysBoundedUnderRepeatedMisses`, `.reopeningEvictedBlockStillReadsCorrectly`, `.markUnavailableInvalidatesCachedMapping`. Without this bound, a system with 10k+ blocks could keep one FileChannel per block open indefinitely.

## Reproduction

```powershell
# Terminal 1: backend (make sure data-dir is clean)
Remove-Item -Recurse -Force backend/data ; New-Item -ItemType Directory backend/data
cd backend
.\mvnw.cmd -q spring-boot:run

# Terminal 2: end-to-end bundle
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 50000 --batch 100 --hours 24
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant acme-inc  --count 30000 --batch 100 --hours 24 --seed 99
java -cp target/classes com.blocklog.demo.MeasureCompression --data-dir data --url http://localhost:8080
java -cp target/classes com.blocklog.demo.MeasureSearch --tenant demo-shop --warmup 30 --iterations 300 --hours 24

# Terminal 2 (later): memory: idle sample first, then a load-vs-sample overlap
java -cp target/classes com.blocklog.demo.MeasureMemory --seconds 20 --interval 500
Start-Job { java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant mem-load --count 100000 --batch 100 --seed 111 }
java -cp target/classes com.blocklog.demo.MeasureMemory --seconds 20 --interval 500

# Terminal 2: JMH baseline
.\mvnw.cmd -B -Pjmh -DskipTests clean compile
.\mvnw.cmd -B -Pjmh -DskipTests dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = "target\classes;" + (Get-Content cp.txt)
java -cp $cp org.openjdk.jmh.Main -f 1 -wi 2 -w 1 -i 3 -r 1 -rf json -rff ..\artifacts\jmh-baseline.json
```

Same seeds produce same records byte-for-byte. Same measurement command produces statistically similar latencies subject to OS scheduling and page-cache warmth.

## Run ledger

| Date | Run ID | Scope | Outcome | Evidence |
| --- | --- | --- | --- | --- |
| 2026-09-16 | (initial suite) | JUnit + integration (29 tests at that date) | 29/29 pass in ~6 s | `artifacts/test-session9.log`, surefire reports |
| 2026-09-16 | demo-2026-09-16-a | End-to-end workload (demo-shop 50k + acme-inc 30k) | 80,000 records buffered / persisted / searchable. 3.86x compression. p95 search 75 ms client / 74 ms engine. 33.3% prune | `artifacts/seed-50k.log`, `artifacts/compression-50k.log`, `artifacts/search-latency-multitenant.log` |
| 2026-09-16 | jmh-2026-09-16-a | JMH codec baseline, 3 methods x 6 param combos | Best case decode 31.7M ops/sec at 64 B / 0 tags; worst case encode 0.7M ops/sec at 2 KB / 4 tags | `artifacts/jmh-baseline.json`, `artifacts/jmh-run.log` |
| 2026-09-18 | jmh-scan-2026-09-18-a | JMH ScanBenchmark, 3 of 4 param combos across two methods | scanFullRangeText: 0.45 / 1.82 / 6.62 ms avgt at 2k / 8k / 32k records. scanTagFilteredText: 0.23 / 0.88 / 3.88 ms avgt (tag pre-check ~2x cheaper) | `artifacts/jmh-scan-run.log`, `artifacts/jmh-scan-attempt.json` |
| 2026-09-16 | memory-2026-09-16-a | Heap + queue/buffer sampling, 20 s idle then 20 s under 100k-record ingest | Idle heap 28 MB mean; loaded heap 143 MB mean; +115 MB delta under load. Peak buffer 3.17 MB | `artifacts/memory-idle.log`, `artifacts/memory-loaded.log` |
| 2026-09-16 | (frontend build) | Frontend build + typecheck | `npm ci && npm run build` succeeds; 5 static routes prerendered | `artifacts/ci-frontend-build.log` |
