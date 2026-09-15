package com.blocklog.metadata;

import com.blocklog.model.LogRecord;
import com.blocklog.storage.BlockWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restart-discovery contract: an engine process that dies mid-run must
 * rebuild its catalog from the block files on disk, and the recovered
 * catalog must be searchable with the same tenant/time/tag boundaries the
 * originals had.
 *
 * These tests write real .blk files with BlockWriter, then construct a
 * fresh BlockCatalog against the same directory — the same mechanism the
 * live engine uses when {@link BlockCatalog#discoverBlocks(Path)} is
 * called on start-up.
 */
class BlockCatalogRestartTest {

    @TempDir
    Path dataDir;

    @Test
    void rediscoversBlocksAcrossFreshCatalog() throws Exception {
        BlockWriter.writeBlock(dataDir, "tenant-a", List.of(
                new LogRecord(1000L, Map.of("env", "prod"), "record a1"),
                new LogRecord(2000L, Map.of("env", "prod"), "record a2")
        ), "block-a1");
        BlockWriter.writeBlock(dataDir, "tenant-a", List.of(
                new LogRecord(3000L, Map.of("env", "staging"), "record a3")
        ), "block-a2");
        BlockWriter.writeBlock(dataDir, "tenant-b", List.of(
                new LogRecord(5000L, Map.of(), "record b1")
        ), "block-b1");

        BlockCatalog reloaded = new BlockCatalog(1000);
        try {
            reloaded.discoverBlocks(dataDir);

            assertEquals(3, reloaded.size(), "restart must find every .blk file");
            assertEquals(0, reloaded.unavailableCount(), "healthy blocks stay available");

            var tenantA = reloaded.candidatesForQuery("tenant-a", 0, 10_000, null).candidates();
            assertEquals(2, tenantA.size(), "tenant-a must have exactly its own two blocks after restart");
            assertTrue(tenantA.stream().allMatch(m -> m.tenantId().equals("tenant-a")));

            var tenantB = reloaded.candidatesForQuery("tenant-b", 0, 10_000, null).candidates();
            assertEquals(1, tenantB.size(), "tenant-b keeps its block after restart");
        } finally {
            reloaded.close();
        }
    }

    @Test
    void ignoresTempAndBogusFilesDuringDiscovery() throws Exception {
        BlockWriter.writeBlock(dataDir, "tenant-a", List.of(
                new LogRecord(1000L, Map.of(), "real")
        ), "real-block");

        // Leftover from a crashed flush; must be ignored by discovery.
        Files.writeString(dataDir.resolve("real-block.blk.tmp.abc123"), "half-written");
        // Non-.blk stray files must not confuse the scan either.
        Files.writeString(dataDir.resolve("notes.txt"), "hello");

        BlockCatalog reloaded = new BlockCatalog(1000);
        try {
            reloaded.discoverBlocks(dataDir);
            assertEquals(1, reloaded.size());
            assertEquals(0, reloaded.unavailableCount());
        } finally {
            reloaded.close();
        }
    }

    @Test
    void corruptBlockFileIsRegisteredAsUnavailable() throws Exception {
        BlockWriter.writeBlock(dataDir, "tenant-a", List.of(
                new LogRecord(1000L, Map.of(), "good")
        ), "good-block");

        // Truncated block file: a magic-sized preamble but no valid header body.
        Path bad = dataDir.resolve("truncated-block.blk");
        Files.write(bad, new byte[]{'B', 'L', 'O', 'G', 0, 1, 0, 0, 0, 4, 1, 2, 3, 4});

        BlockCatalog reloaded = new BlockCatalog(1000);
        try {
            reloaded.discoverBlocks(dataDir);
            assertEquals(2, reloaded.size(), "corrupt blocks are still catalog entries so partial results can report them");
            assertEquals(1, reloaded.unavailableCount(), "the truncated file must be tagged unavailable");
        } finally {
            reloaded.close();
        }
    }

    @Test
    void payloadCorruptionIsSurfacedOnMappingLoad() throws Exception {
        Path file = BlockWriter.writeBlock(dataDir, "tenant-a", List.of(
                new LogRecord(1000L, Map.of(), "will be corrupted")
        ), "victim-block");

        // Flip a byte deep in the payload region so the payload CRC fails
        // but the header CRC still verifies. Discovery only reads the header
        // so the block passes discovery; the corruption must be reported
        // when a search tries to actually read it.
        long size;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            size = ch.size();
        }
        // Payload starts after the fixed prefix + header body + header CRC.
        // Corrupt a byte 8 bytes before the end (safely inside the compressed payload).
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long pos = size - 8;
            var bb = java.nio.ByteBuffer.allocate(1);
            ch.read(bb, pos);
            bb.rewind();
            bb.put(0, (byte) (bb.get(0) ^ 0x55));
            bb.rewind();
            ch.write(bb, pos);
        }

        BlockCatalog reloaded = new BlockCatalog(1000);
        try {
            reloaded.discoverBlocks(dataDir);
            assertEquals(1, reloaded.size());
            // Header still passes CRC, so the block is discoverable...
            assertEquals(0, reloaded.unavailableCount(), "header CRC survives payload corruption");

            // ...but when a scanner asks for a mapping and tries to read
            // records, the payload CRC will fail and the catalog is told
            // to mark it unavailable.
            var meta = reloaded.snapshot().get(0);
            var mapping = reloaded.mappingFor(meta);
            assertNotNull(mapping);
            var header = com.blocklog.storage.BlockReader.parseHeaderFromMapping(mapping);
            assertThrows(com.blocklog.storage.BlockReader.BlockCorruptException.class,
                    () -> com.blocklog.storage.BlockReader.readRecords(mapping, header));
        } finally {
            reloaded.close();
        }
    }

}
