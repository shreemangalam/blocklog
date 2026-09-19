package com.blocklog.search;

import com.blocklog.metadata.BlockCatalog;
import com.blocklog.metadata.BlockMetadata;
import com.blocklog.model.LogRecord;
import com.blocklog.model.SearchResponse;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance criterion #2 in functional-design.md: search results equal a
 * naive record-by-record reference scan. This test asserts that no matter
 * what predicates a query carries (tenant, time window, tag AND, keyword)
 * {@link SearchEngine#search} returns the same set of matches (by
 * timestamp + message identity) as a straightforward reference implementation
 * over the same source data.
 *
 * If any prune step ever emits a false negative, or if the LZ4 payload is
 * miscounted or a scan skips records silently, this test fails.
 *
 * The reference scan below intentionally does NOT touch mmap, decompression,
 * or block metadata; it iterates the source records that were written and
 * applies the same predicate semantics documented in {@code /help}: exact
 * tenant, half-open {@code [from, to)}, case-sensitive substring, tag AND.
 */
class ReferenceScanEquivalenceTest {

    @TempDir
    Path dataDir;

    private BlockCatalog catalog;

    @AfterEach
    void tearDown() {
        if (catalog != null) catalog.close();
    }

    @Test
    void searchEqualsReferenceScanAcrossManyRandomQueries() throws Exception {
        catalog = new BlockCatalog(1000);

        // Deterministic source dataset. Two tenants across three services and
        // two envs, split into 4 blocks so pruning has room to fire.
        Random rng = new Random(1234);
        List<Sourced> universe = new ArrayList<>();
        String[] tenants = {"tenant-a", "tenant-b"};
        String[] services = {"checkout", "inventory", "search"};
        String[] envs = {"prod", "staging"};
        String[] keywords = {"payment", "timeout", "slow", "ok"};

        int blockIndex = 0;
        for (String tenant : tenants) {
            for (int block = 0; block < 2; block++) {
                List<LogRecord> blockRecords = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    long ts = 1_700_000_000_000L + blockIndex * 100_000L + i * 1_000L;
                    Map<String, String> tags = new HashMap<>();
                    tags.put("env", envs[rng.nextInt(envs.length)]);
                    tags.put("service", services[rng.nextInt(services.length)]);
                    String msg = keywords[rng.nextInt(keywords.length)] + " event " + i;
                    LogRecord rec = new LogRecord(ts, tags, msg);
                    blockRecords.add(rec);
                    universe.add(new Sourced(tenant, rec));
                }
                String blockId = tenant + "-block-" + block;
                Path file = BlockWriter.writeBlock(dataDir, tenant, blockRecords, blockId);
                catalog.register(BlockMetadata.fromHeader(blockId, file, BlockReader.readHeader(file)));
                blockIndex++;
            }
        }

        EngineMetrics metrics = new EngineMetrics(new SimpleMeterRegistry());
        SearchEngine engine = new SearchEngine(catalog, 4, 4, metrics);

        // Run 25 random queries covering the full predicate matrix.
        for (int q = 0; q < 25; q++) {
            String tenant = tenants[rng.nextInt(tenants.length)];
            long from = 1_700_000_000_000L + rng.nextInt(400) * 500L;
            long to = from + 1 + rng.nextInt(300_000);

            Map<String, String> tagFilter = null;
            if (rng.nextBoolean()) {
                tagFilter = new HashMap<>();
                if (rng.nextBoolean()) tagFilter.put("env", envs[rng.nextInt(envs.length)]);
                if (rng.nextBoolean()) tagFilter.put("service", services[rng.nextInt(services.length)]);
                if (tagFilter.isEmpty()) tagFilter = null;
            }

            String text = null;
            if (rng.nextBoolean()) {
                text = keywords[rng.nextInt(keywords.length)];
            }

            // Engine result (bounded top-K by earliest timestamp, but ask for
            // a huge limit so truncation is out of scope here).
            SearchResponse response = engine.search(tenant, from, to, tagFilter, text, 1000, 5000);
            assertFalse(response.partial(), "no timed-out or unavailable-blocks in a healthy corpus");

            Set<String> engineHits = response.results().stream()
                    .map(h -> h.timestamp() + "|" + h.message())
                    .collect(Collectors.toSet());

            // Reference scan.
            final String tenantF = tenant;
            final long fromF = from, toF = to;
            final Map<String, String> tagsF = tagFilter;
            final String textF = text;
            Set<String> referenceHits = universe.stream()
                    .filter(s -> s.tenant.equals(tenantF))
                    .filter(s -> s.record.timestamp() >= fromF && s.record.timestamp() < toF)
                    .filter(s -> {
                        if (tagsF == null) return true;
                        for (var e : tagsF.entrySet()) {
                            String have = s.record.tags().get(e.getKey());
                            if (have == null || !have.equals(e.getValue())) return false;
                        }
                        return true;
                    })
                    .filter(s -> textF == null || s.record.message().contains(textF))
                    .map(s -> java.time.Instant.ofEpochMilli(s.record.timestamp()).toString()
                            + "|" + s.record.message())
                    .collect(Collectors.toSet());

            assertEquals(referenceHits, engineHits, () ->
                    "query mismatch: tenant=" + tenantF
                            + " [" + fromF + "," + toF + ") tags=" + tagsF + " text=" + textF
                            + "\n  reference=" + referenceHits
                            + "\n  engine=" + engineHits);
        }
    }

    @Test
    void searchIsStableUnderRepeatedQueries() throws Exception {
        catalog = new BlockCatalog(1000);
        List<LogRecord> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(new LogRecord(1_000L + i * 10L,
                    Map.of("env", "prod"),
                    "event " + i));
        }
        Path file = BlockWriter.writeBlock(dataDir, "t1", records, "b1");
        catalog.register(BlockMetadata.fromHeader("b1", file, BlockReader.readHeader(file)));

        SearchEngine engine = new SearchEngine(catalog, 2, 4,
                new EngineMetrics(new SimpleMeterRegistry()));

        SearchResponse first = engine.search("t1", 0, 100_000, null, "event", 1000, 5000);
        List<String> firstOrder = first.results().stream()
                .map(r -> r.timestamp() + "|" + r.message())
                .collect(Collectors.toList());
        assertEquals(20, firstOrder.size());
        for (int i = 0; i < 5; i++) {
            SearchResponse later = engine.search("t1", 0, 100_000, null, "event", 1000, 5000);
            List<String> laterOrder = later.results().stream()
                    .map(r -> r.timestamp() + "|" + r.message())
                    .collect(Collectors.toList());
            assertEquals(firstOrder, laterOrder,
                    "repeated searches on an unchanged corpus must return identical ordering");
        }

        // And the ordering must be by ascending timestamp per the contract.
        List<String> expected = new ArrayList<>(firstOrder);
        expected.sort(Comparator.comparing(s -> s.split("\\|")[0]));
        assertEquals(expected, firstOrder,
                "results must be earliest-first by timestamp");
    }

    private record Sourced(String tenant, LogRecord record) {}
}
