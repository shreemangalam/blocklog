# BlockLog

**An index-free log storage engine for incident-response search.** Immutable LZ4-compressed blocks on disk, an in-memory summary catalog for tenant / time / tag pruning, and virtual-thread scans over the surviving candidates. Every response tells you exactly what it saw. No silent partial answers, no unbounded query time.

Single-node prototype. Not a production database.

![BlockLog architecture](docs/public/architecture.svg)

---

## Why I built this

Most log search tools answer "find me events matching X" by building an inverted index: every word in every message becomes a key that points to a list of matching records. That works well when you don't know what you're looking for ahead of time. Incident response is the opposite: you already know the tenant, you already know roughly when the problem started, and you have a tag or a keyword in mind. The index becomes overhead rather than a shortcut.

BlockLog makes the opposite bet. Skip the index entirely. Instead, keep a tiny in-memory summary of every block on disk — its tenant, its time range, and a bloom-like tag summary — and use that to prune 90%+ of blocks before reading a single compressed byte. What survives goes to parallel virtual-thread scans with nanosecond deadlines. The result is a system whose search cost scales with how well you know what you're looking for, not with total data volume.

The other thing I wanted to get right was honesty. Most systems return results and say nothing about what they skipped. BlockLog returns `candidate_blocks`, `scanned_blocks`, `unavailable_blocks`, `partial`, `truncated`, and `timed_out` on every response. A partial result is labelled partial. A corrupted block is reported, not silently dropped. That transparency is the core design principle.

---

## What it is

Every log record is a note. Every search asks *"which of the last N notes match this tenant, this time window, these tags, this keyword?"*. Most systems answer that by building a giant per-word inverted index. BlockLog skips the index and pays for the scan instead. In incident-response you already know the tenant, the time window, and roughly what you're looking for, so pruning cuts the work by 90%+ before any byte is read.

- **Ingest**: HTTP → validate → reserve bytes on a queue budget → jctools MPSC queue → single consumer drains into per-tenant buffers → flush at 5 MB or 5 s → LZ4 + CRC32 → atomic rename → catalog register. 202 buffered ≠ durable; documented in `/help`.
- **Storage**: one immutable file per block. Header + payload CRCs. Restart discovery rebuilds the catalog from disk.
- **Search**: tenant / time / tag prune from an in-memory summary; virtual-thread-per-task scan on the survivors; deadline in nanoseconds; every response carries `candidate_blocks`, `scanned_blocks`, `unavailable_blocks`, `partial`, `truncated`, `timed_out`, `elapsed_ms`.
- **Frontend**: Next.js App Router / TypeScript / Tailwind / shadcn/ui. Strict light theme. `/`, `/status`, `/help`, `/onboarding`.

---

## Measured evidence (2026-09-16)

Full bundle in [`docs/public/testing-evidence.md`](docs/public/testing-evidence.md).

| Metric | Result |
| --- | --- |
| Ingestion (single client thread, batch 100) | **31,210 records/sec**, ~4.1 MB/s raw |
| Compression ratio | **3.86×** on realistic synthetic logs |
| Search p95 (mixed workload, 300 queries) | **75 ms client / 74 ms engine** |
| Search p99 | 81 ms / 79 ms |
| Cross-tenant prune | **33.3%** (2 of 3 blocks survive tenant filter) |
| Memory (idle → loaded) | 28 MB → 143 MB heap during 100k ingest |
| JMH codec best case | decode **31.7 M ops/sec** at 64 B / 0 tags |
| Correctness suite | **37 tests, all passing**: round-trip, restart discovery, corruption injection, mmap eviction, HTTP 429/503, cross-tenant isolation, deadline honoring, error surface, search equivalence |

---

## Quick start

Prerequisites: JDK 21 (Temurin), Node 20+, Windows PowerShell or a POSIX shell.

**Backend**

```bash
cd backend
./mvnw -q spring-boot:run
# API on http://localhost:8080
```

**Frontend** (separate terminal)

```bash
cd frontend
npm ci
npm run dev
# UI on http://localhost:3000
```

**Seed a demo tenant with 1000 realistic records**

```bash
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 1000
```

Or on Windows: `.\scripts\seed-demo.ps1 -Tenant demo-shop -Count 1000`.

Open [http://localhost:3000](http://localhost:3000), type tenant `demo-shop`, pick a wide time range (**inputs are UTC**), and search. The structured form is the primary interface. `/status` shows live counters, `/help` explains the semantics, `/onboarding` runs a three-step guided walkthrough.

---

## Reproduce the evidence bundle

```bash
cd backend
# 1. Ingest
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 50000 --batch 100
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant acme-inc  --count 30000 --batch 100 --seed 99

# 2. Compression
java -cp target/classes com.blocklog.demo.MeasureCompression --data-dir data

# 3. Search latency (six query classes, p50/p95/p99)
java -cp target/classes com.blocklog.demo.MeasureSearch --tenant demo-shop --warmup 30 --iterations 300

# 4. Memory (run alongside a load; see docs)
java -cp target/classes com.blocklog.demo.MeasureMemory --seconds 20

# 5. JMH codec baseline (~1 min)
./mvnw -B -Pjmh -DskipTests clean compile
./mvnw -B -Pjmh -DskipTests dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" org.openjdk.jmh.Main -f 1 -wi 2 -w 1 -i 3 -r 1 -rf json -rff ../artifacts/jmh-baseline.json
```

Same seeds → same records byte-for-byte. See [`docs/public/testing-evidence.md`](docs/public/testing-evidence.md) for full commands and interpretation.

---

## Demo the "honest partiality" story

The engine promises: corruption becomes visibility, not silence. To watch that fire end-to-end:

```bash
# 1. Seed a small tenant, wait for the block to publish
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 1000

# 2. Flip one byte deep in a block's compressed payload
ls data/*.blk
java -cp target/classes com.blocklog.demo.CorruptBlock --file data/<uuid>.blk

# 3. Restart the backend so the catalog rebuilds from disk
#    (Ctrl+C the running server, then relaunch)
./mvnw -q spring-boot:run

# 4. Search overlapping the corrupted block's time range
curl -sS -X POST http://localhost:8080/api/v1/search -H 'Content-Type: application/json' -d '{
  "tenant_id": "demo-shop",
  "from": "2020-01-01T00:00:00Z",
  "to": "2030-01-01T00:00:00Z",
  "text": null,
  "tags": null,
  "limit": 100,
  "timeout_ms": 5000
}' | jq '{partial, unavailable_blocks, scanned_blocks, candidate_blocks}'
```

Response carries `partial: true`, `unavailable_blocks: 1`, and `scanned_blocks < candidate_blocks`. The block passed header CRC at discovery (still counted as a candidate) but the payload CRC caught the flip at scan time. The block degrades to unavailable on the first scan and the response reports it honestly. Check `/status` for `unavailable_blocks >= 1`.

---

## Tech stack

**Backend**: Java 21, virtual threads, Spring Boot 3.4, `java.nio`, lz4-java, jctools MPSC queue, Caffeine bounded mmap cache, Micrometer + Prometheus metrics. JUnit 5 + JMH.

**Frontend**: Next.js App Router 16, TypeScript, Tailwind CSS, shadcn/ui, strict light theme.

**NL Search** (opt-in, currently inert without an API key): a language model API translates plain English to the structured search JSON. Structured form remains the primary interface; NL is additive and the project functions fully without it.

---

## Repository layout

```text
backend/                                Java 21 engine + Spring Boot API
  src/main/java/com/blocklog/
    api/                                REST controllers + Spring wiring
    ingest/                             IngestionEngine, ByteBudget, MPSC queue, typed 429/503 exceptions
    metadata/                           BlockCatalog (bounded mmap cache), BlockMetadata, PruneResult
    model/                              EngineConfig, request/response DTOs, LogRecord
    observability/                      EngineMetrics (Micrometer)
    search/                             SearchEngine (virtual-thread scan, deadline)
    storage/                            BlockWriter, BlockReader, BlockMapping, RecordCodec
    demo/                               SyntheticIngest, MeasureSearch, MeasureCompression, MeasureMemory
  src/test/java/com/blocklog/           37 unit + integration tests
  src/jmh/java/com/blocklog/bench/      CodecBenchmark, ScanBenchmark
frontend/                               Next.js 16 App Router UI
docs/public/
  functional-design.md
  architecture.svg
  testing-evidence.md                   Measured bundle with reproduction commands
scripts/
  seed-demo.ps1                         One-command demo tenant on Windows
.github/workflows/ci.yml                Backend test + frontend build on push/PR
```

---

## What this isn't, stated plainly

- **No write-ahead log this sprint.** A crash between the 202 acknowledgement and block publish can lose queued or unflushed records. Potentially seconds of traffic under backlog. `/help` says this.
- **No authentication.** `tenant_id` is a string a client sends; the engine trusts it. Enforcement layers are a follow-up, not a redesign.
- **No full-text index, no ranking, no fuzzy matching, no regex.** Case-sensitive substring only.
- **Not distributed, not replicated, not multi-node.**
- **Not measured on hardware other than one Windows laptop.** Numbers are localhost-only; network hops would dominate the ~40 ms engine time.

This is intentional scope for a sprint-sized prototype.

---

## License

See [`LICENSE`](LICENSE).
