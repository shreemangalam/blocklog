package com.blocklog.metadata;

import com.blocklog.model.LogRecord;
import com.blocklog.storage.BlockMapping;
import com.blocklog.storage.BlockReader;
import com.blocklog.storage.BlockWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mmap cache in {@link BlockCatalog} is bounded. Under sustained
 * unique-block queries it must evict the least-recently-used mapping and
 * close its FileChannel instead of growing without limit.
 */
class MmapCacheEvictionTest {

    @TempDir
    Path dataDir;

    private BlockCatalog catalog;

    @AfterEach
    void tearDown() {
        if (catalog != null) catalog.close();
    }

    @Test
    void cacheStaysBoundedUnderRepeatedMisses() throws Exception {
        int cacheSize = 2;
        catalog = new BlockCatalog(100, cacheSize);

        List<BlockMetadata> metas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var records = List.of(new LogRecord(1000L + i, Map.of(), "record " + i));
            Path file = BlockWriter.writeBlock(dataDir, "tenant-a", records, "b" + i);
            var header = BlockReader.readHeader(file);
            var meta = BlockMetadata.fromHeader("b" + i, file, header);
            catalog.register(meta);
            metas.add(meta);
        }

        // Touch every block once. Cache is size 2 so at most 2 mappings live.
        for (BlockMetadata meta : metas) {
            BlockMapping mapping = catalog.mappingFor(meta);
            assertNotNull(mapping, "mapping must open");
            assertTrue(mapping.size() > 0);
        }

        assertTrue(catalog.cachedMappingCount() <= cacheSize,
                "cache must stay bounded by size=" + cacheSize
                        + ", actual=" + catalog.cachedMappingCount());
    }

    @Test
    void reopeningEvictedBlockStillReadsCorrectly() throws Exception {
        catalog = new BlockCatalog(100, 1);

        Path fileA = BlockWriter.writeBlock(dataDir, "tenant-a",
                List.of(new LogRecord(1L, Map.of(), "a")), "a");
        Path fileB = BlockWriter.writeBlock(dataDir, "tenant-a",
                List.of(new LogRecord(2L, Map.of(), "b")), "b");
        var metaA = BlockMetadata.fromHeader("a", fileA, BlockReader.readHeader(fileA));
        var metaB = BlockMetadata.fromHeader("b", fileB, BlockReader.readHeader(fileB));
        catalog.register(metaA);
        catalog.register(metaB);

        // Prime the cache and force enough churn that A must be evicted at
        // least once. Under a size-1 cache, requesting many blocks in
        // alternation means A is repeatedly reloaded from disk.
        for (int i = 0; i < 20; i++) {
            BlockMapping loadedA = catalog.mappingFor(metaA);
            assertNotNull(loadedA);
            var headerA = BlockReader.parseHeaderFromMapping(loadedA);
            assertEquals("tenant-a", headerA.tenantId());
            assertEquals(1, headerA.recordCount());

            BlockMapping loadedB = catalog.mappingFor(metaB);
            assertNotNull(loadedB);
            assertTrue(loadedB.size() > 0);
        }

        // Bounded cache must still be honoring its size after all this churn.
        assertTrue(catalog.cachedMappingCount() <= 1);
    }

    @Test
    void markUnavailableInvalidatesCachedMapping() throws Exception {
        catalog = new BlockCatalog(100, 8);

        Path file = BlockWriter.writeBlock(dataDir, "tenant-a",
                List.of(new LogRecord(1L, Map.of(), "x")), "block-x");
        var meta = BlockMetadata.fromHeader("block-x", file, BlockReader.readHeader(file));
        catalog.register(meta);

        BlockMapping first = catalog.mappingFor(meta);
        assertNotNull(first);
        assertEquals(1, catalog.cachedMappingCount());

        catalog.markUnavailable("block-x");

        assertEquals(0, catalog.cachedMappingCount(),
                "the removed mapping must be evicted from the cache");
        assertEquals(1, catalog.unavailableCount());
    }
}
