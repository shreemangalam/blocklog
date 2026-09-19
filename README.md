# BlockLog

[![CI](https://github.com/shreemangalam/blocklog/actions/workflows/ci.yml/badge.svg)](https://github.com/shreemangalam/blocklog/actions/workflows/ci.yml)
&nbsp;![Java 21](https://img.shields.io/badge/Java-21-blue?logo=openjdk&logoColor=white)
&nbsp;![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-brightgreen?logo=springboot&logoColor=white)
&nbsp;![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js&logoColor=white)

A log storage engine built around one bet: for incident response, you almost always know what you are looking for before you start. You know the tenant. You have a rough time window. You have a tag or a keyword. An inverted index is overhead you pay to handle queries you will never run.

BlockLog skips the index. Records land in LZ4-compressed, CRC32-checksummed immutable blocks on disk. A small in-memory catalog holds one entry per block: tenant, time range, tag summary. At query time the catalog prunes candidates in microseconds, then each surviving block gets a dedicated Java virtual thread. The engine tells you exactly what it saw on every response: candidate blocks, scanned blocks, unavailable blocks, and whether the result is complete.

Single-node prototype. Not a production database.

![BlockLog architecture](docs/public/architecture.svg)

---

## The design decision

Most log search tools build an inverted index: every word in every message maps back to the records that contain it. That generalises well when you don't know what you'll query. Incident response is different.

A responder looking for payment failures in the last two hours for a specific service doesn't need the index. They need the engine to skip the blocks that can't match, decompress the ones that can, and return the earliest results before the deadline. The in-memory catalog and CRC32 checksums are the only metadata. Everything else is in the data.

The second principle is honesty. Most systems return results and stay silent about what they skipped. BlockLog reports everything on every response: `candidate_blocks`, `scanned_blocks`, `unavailable_blocks`, `partial`, `truncated`, `timed_out`, `elapsed_ms`. A corrupted block is surfaced and counted, not silently dropped.

---

## Numbers

Measured on a single Windows laptop, all traffic on localhost. Same seeds reproduce the same records byte-for-byte.
Full commands and interpretation in [`docs/public/testing-evidence.md`](docs/public/testing-evidence.md).

| Metric | Result |
|---|---|
| Ingestion throughput | **31,210 records/sec** (single client thread, batch size 100) |
| Compression ratio | **3.86x** on realistic synthetic microservice logs |
| Search p95 | **75 ms** client-side / **74 ms** engine-only across 300 queries |
| Search p99 | 81 ms / 79 ms |
| Memory (idle to loaded) | 28 MB heap up to 143 MB under 100k record ingest |
| JMH codec throughput | **31.7M decode ops/sec** at 64 bytes, 0 tags |
| Test suite | **42 tests** passing (round-trip codec, restart discovery, corruption injection, mmap eviction, deadline enforcement, concurrent writers, cross-tenant isolation, input validation, error surface) |

---

## How it works

**Ingest.** An HTTP request validates fields and checks the per-tenant byte budget, returning 429 if exhausted. Accepted records go onto a jctools MPSC queue; the HTTP thread returns `202 Buffered` immediately. A single consumer thread drains the queue into per-tenant in-memory buffers. A flush triggers at 5 MB or 5 seconds, whichever comes first: LZ4 compress, CRC32 on header and payload, write to `.blk.tmp`, fsync, atomic rename to `.blk`, register in catalog. `202` means in memory, not on disk. A crash before the next flush loses those records.

**Search.** The catalog prunes candidates by exact tenant, overlapping time range, and tag union. Each surviving block gets one virtual thread. The thread mmaps the file, verifies the payload CRC, decompresses with LZ4, and filters records by time, tags, and text substring, checking the deadline every 256 records. Results merge through a min-heap for earliest-K ordering. Every response carries completeness metadata.

**Frontend.** Next.js App Router with a structured search form, a natural-language search tab (translates plain English to the structured query schema; requires an API key, gracefully inert without one), a live engine status page, a reference at `/help`, and a guided walkthrough at `/onboarding`.

---

## Getting started

Requires JDK 21 (Temurin) and Node 20+.

```bash
# Terminal 1: backend API on :8080
cd backend && mvnw.cmd -q spring-boot:run   # Windows; use mvn on Linux/macOS

# Terminal 2: frontend on :3000
cd frontend && npm ci && npm run dev

# Terminal 3: seed a demo tenant
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 1000
```

Open `http://localhost:3000`, set tenant to `demo-shop`, pick any UTC time range, and search. `/status` shows live counters. `/help` explains every response field. `/onboarding` walks through ingest, flush, and search with the actual request and response bodies shown inline.

---

## Corruption demo

The most interesting thing to run. Flip one byte inside a block's compressed payload, restart the backend, and search across that time range. The engine detects the CRC failure at read time and reports the block as unavailable. It does not crash, does not silently exclude it, and does not pretend the result is complete.

```bash
# 1. Seed data and wait ~5 seconds for the flush trigger
cd backend
java -cp target/classes com.blocklog.demo.SyntheticIngest --tenant demo-shop --count 500

# 2. Flip one byte in a block's payload
ls data/*.blk
java -cp target/classes com.blocklog.demo.CorruptBlock --file data/<uuid>.blk

# 3. Restart the backend so the catalog rebuilds from disk
mvnw.cmd -q spring-boot:run

# 4. Search across the corrupted block's time range
curl -s -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"demo-shop","from":"2020-01-01T00:00:00Z","to":"2030-01-01T00:00:00Z","limit":10,"timeout_ms":5000}' \
  | jq '{partial, unavailable_blocks, scanned_blocks, candidate_blocks}'
```

```json
{
  "partial": true,
  "unavailable_blocks": 1,
  "scanned_blocks": 0,
  "candidate_blocks": 1
}
```

The block passed header CRC at startup so it was counted as a candidate. The payload CRC failed on the first read, so it was marked unavailable immediately. Results from any healthy blocks in the same query still come back normally.

---

## Stack

**Backend:** Java 21, virtual threads (Project Loom), Spring Boot 3.4, `java.nio`, lz4-java, jctools MPSC queue, Caffeine bounded mmap cache, Micrometer + Prometheus metrics. JUnit 5, JMH.

**Frontend:** Next.js 16 App Router, TypeScript, Tailwind CSS, shadcn/ui. Strict light theme throughout.

---

## What this deliberately isn't

- **No write-ahead log.** `202 Buffered` is an in-memory acknowledgement. A crash between acceptance and flush loses queued records.
- **No authentication.** `tenant_id` is client-supplied. A production deployment needs an enforcement layer in front of the ingest endpoint.
- **No distributed anything.** Single writer, single node, no replication.
- **No full-text search, no ranking, no fuzzy matching, no regex.** Case-sensitive substring only.
- **Numbers are from one laptop.** All measurements are localhost; network latency would dominate the ~74 ms engine time.

---

## License

[LICENSE](LICENSE)
