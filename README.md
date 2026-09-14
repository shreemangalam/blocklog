# BlockLog

BlockLog is a planned single-node log storage engine for incident response. It stores opaque log messages in LZ4-compressed binary blocks and searches candidate blocks after filtering lightweight tenant, time, and tag metadata. It does not build a full-text inverted index.

## Status

Design and repository skeleton, reviewed on 2026-09-12. The engine, API, frontend, build configuration, CI, and benchmarks are not implemented. No performance results have been measured. This is a seven-day engineering prototype with production-oriented correctness goals, not a production certification.

## Design and evidence

- [Functional design](docs/public/functional-design.md): scope, user journeys, API behavior, acceptance criteria.
- [Technical design](docs/public/technical-design.md): storage, concurrency, recovery, resource limits, and LLM-assisted query translation.

## Repository layout

```text
backend/                 Java 21 engine and Spring Boot API; Maven build pending
  src/main/java/com/blocklog/
  src/test/java/com/blocklog/
frontend/                Next.js application; scaffold pending
docs/public/             Shareable design and evidence
.github/workflows/       CI configuration pending
docker-compose.yml       Local orchestration placeholder
```

Keep one backend Maven module and one frontend application for this sprint. Add engine packages, resources, fixtures, and benchmarks as their first implementations arrive. Empty directories are not preserved by Git.

## Intended stack and workflow

Java 21, Spring Boot 3 for the HTTP boundary, java.nio, lz4-java, JUnit 5, JMH, Testcontainers, and Micrometer. Frontend: Next.js App Router, TypeScript, Tailwind CSS, and shadcn/ui, using a light theme. Natural-language query mode uses the Anthropic Messages API for structured query translation.

Implement on `test`, validate the current commit, then merge to `main`. Correctness tests gate changes; relevant JMH benchmarks provide performance evidence. Benchmark numbers require a recorded environment and workload. Setup and executable commands will be added when the build exists.

## Scope boundary

Deliver ingestion, compressed persistence, restart recovery, metadata pruning, bounded substring search, a small incident-response interface with structured and natural-language search modes, and four measured metrics. Distributed storage, replication, full-text indexing, a query language, and managed cloud deployment are outside this sprint.
