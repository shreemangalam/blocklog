# BlockLog: Interview Questions and Answer Notes

Status: design discussion, 2026-09-12, updated 2026-09-14. Replace proposed behavior with implementation details only after verification. Use the testing evidence document for measured numbers; no performance claims are established yet.

## 1. What problem does BlockLog solve?

It explores the tradeoff between cheap log ingestion and more expensive incident-time search. Messages are stored in compressed blocks without a full-text inverted index; block metadata reduces the data searched when tenant/time/tag filters are selective. Broad searches remain expensive.

## 2. Is it actually index-free?

It is full-text-index-free. It still has a lightweight block metadata catalog. Saying "no indexes at all" would misdescribe the system. Pruning effectiveness depends on physical grouping and workload selectivity.

## 3. Why LZ4 and a 5 MB / 5 second policy?

LZ4 is the selected codec for the sprint's throughput hypothesis. Size bounds constrain active data, while age bounds trigger publication for low-volume tenants. Five MB is an initial design parameter, not a proven optimum. Record-aligned versus byte-exact filling must be settled explicitly; timing includes scheduling and disk delays. Compare ratios and throughput on several data distributions.

## 4. Do virtual threads make decompression faster?

They provide a convenient task model, but CPU work still requires bounded parallelism. The design limits global scans and active queries. Validate speedup with sequential versus bounded-parallel runs; do not attribute it to thread count alone. The technical design links the Java 21 guidance.

## 5. Why mmap?

It gives byte-buffer access to immutable files and lets the operating system manage paging. Page faults and mapping lifetime still cost resources. The sprint reuses mappings within a finite dataset ceiling and avoids live deletion. Heap measurements alone would miss part of the memory cost.

## 6. What does CRC32 guarantee?

Detection of accidental damage within its limits, not authenticity or repair. The format validates both header metadata and compressed payload, bounds lengths before allocation, and reports unavailable results. One file per block allows searching the next file without trusting a damaged length field.

## 7. What happens when the process crashes after acknowledging a batch?

The proposed `202` acknowledges buffered acceptance. Unpublished records can be lost; retries may duplicate records. Completed files are rediscovered on restart. A WAL and exactly-once semantics are deferred. Do not call buffered acknowledgement durable or promise a five-second maximum loss window under backlog.

## 8. How do you prevent silent false negatives?

Metadata pruning is conservative and verified against a reference scan. Tag summary saturation disables that filter. Unknown/corrupt metadata contributes to an explicit incomplete-results state. Record-level predicates recheck every candidate match, including the tenant and time range.

## 9. How do you bound memory and concurrency?

Reserve bytes across request admission, queued records, active/sealed buffers, results, and metadata; bound counts as well. Use one mutable-buffer owner and global scan permits. Apply backpressure when budgets fill. Measure heap, process memory, mappings, and GC under mixed load, including many low-volume tenants.

## 10. Why one file per block instead of segments?

It keeps publication and corruption boundaries independently discoverable in the one-week scope. The cost is file-count/mapping overhead and a finite sprint dataset ceiling. Segment files, trusted block directories, retention, and mapping cleanup are future design work rather than hidden assumptions.

## 11. How are search results deterministic?

Search a snapshot of published blocks and retain earliest K matches using `(timestamp, block_id, record_ordinal)`. Completion order of concurrent tasks does not define result order. A deadline or damaged block makes the result explicitly partial; no global earliest-K guarantee is made for incomplete work.

## 12. Is tenant filtering a security boundary?

Filtering prevents accidental mixing when implemented correctly. A client choosing its own tenant ID is not authentication. The sprint is a trusted local demonstration; externally exposed multi-tenant service needs authenticated tenant binding and authorization.

## 13. How will you defend the four headline metrics?

Report persisted encoded MB/s including final drain, total-file compression ratio, client-observed p95 across a fixed query corpus, and process/heap/native memory under a named load. Save environment, commit, configuration, raw samples, and failures. JMH isolates hot paths; it does not replace end-to-end measurements.

## 14. What does "enterprise-grade" mean for this sprint?

Clear contracts, bounded resources, predictable failure behavior, reproducible tests, observability, and honest limitations. The deliverable is a rigorously tested prototype, not a claim of high availability, disaster recovery, security certification, or production readiness.

## 15. Why add natural-language search? Isn't it just a gimmick?

It solves a real incident-response problem: during an outage, a responder under pressure should not need to remember exact tenant IDs, ISO-8601 format, or tag syntax. Typing "payment-service errors last 2 hours env=prod" and getting a structured query they can review before executing is genuinely faster than filling out a form.

The implementation is deliberately thin: one stateless API call that translates natural language into the existing search API's JSON schema. The LLM does not touch log data, does not reason about results, and has no memory across queries. The structured form remains the primary interface and the project works completely without the NL feature. This is structured extraction from natural language, which is exactly what language models are reliable at — the search schema has five or six well-typed fields, not open-ended reasoning.

The architectural decision to keep it frontend-only and never send log data to the LLM is itself a defensible design choice: it means the engine's correctness, performance, and security properties are entirely unaffected by the AI layer. Remove it and nothing changes. That separation is the point.

## 16. What happens when the LLM gives a wrong translation?

The generated query is shown to the user as an editable preview before execution. They see the tenant, time range, tags, and text filter and can correct any field. Then the same validated search API processes it — so a wrong translation is caught either by the user in the preview or by the API returning a 400. No silent wrong results. On outright failure (timeout, malformed response, network error), the user gets a clear message and the structured form. The LLM layer has no write path and no persistence — it cannot corrupt engine state.

## 17. What would you improve next, and why defer it now?

Use measurements to choose the next bottleneck: durable ingestion may require a WAL; larger datasets may require segments and retention; untrusted access requires authorization. Each changes the correctness model. First finish and measure the current storage/search path.

For NL search, production deployment would route the LLM call through a backend proxy to protect the API key. Multi-turn refinement ("narrow that to just 500 errors"), suggested queries based on available tenants, and caching repeated translations are natural extensions but each adds complexity that would dilute the sprint's focus on the storage engine.
