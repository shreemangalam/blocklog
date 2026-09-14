package com.blocklog.storage;

import com.blocklog.model.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsRecordsWithTagsAndUnicode() throws Exception {
        List<LogRecord> records = List.of(
                new LogRecord(1_700_000_000_000L, Map.of("env", "prod", "service", "payments"), "Payment failed: timeout"),
                new LogRecord(1_700_000_001_000L, Map.of("env", "prod"), "Multi-line\nmessage with unicode: héllo wörld 日本語"),
                new LogRecord(1_700_000_002_000L, Map.of(), "No tags message")
        );

        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-1");
        assertTrue(Files.exists(file));

        BlockHeader header = BlockReader.readHeader(file);
        assertEquals("tenant-a", header.tenantId());
        assertEquals(3, header.recordCount());
        assertEquals(1_700_000_000_000L, header.minTimestamp());
        assertEquals(1_700_000_002_000L, header.maxTimestamp());

        List<LogRecord> decoded = BlockReader.readRecords(file, header);
        assertEquals(records, decoded);
    }

    @Test
    void detectsCorruptedPayload() throws Exception {
        List<LogRecord> records = List.of(new LogRecord(1_700_000_000_000L, Map.of(), "test message"));
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-corrupt");

        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0xFF;
        Files.write(file, bytes);

        BlockHeader header = BlockReader.readHeader(file);
        assertThrows(BlockReader.BlockCorruptException.class, () -> BlockReader.readRecords(file, header));
    }

    @Test
    void detectsTruncatedFile() throws Exception {
        List<LogRecord> records = List.of(new LogRecord(1_700_000_000_000L, Map.of(), "test message"));
        Path file = BlockWriter.writeBlock(tempDir, "tenant-a", records, "block-trunc");

        byte[] bytes = Files.readAllBytes(file);
        byte[] truncated = new byte[bytes.length / 2];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        Files.write(file, truncated);

        assertThrows(BlockReader.BlockCorruptException.class, () -> BlockReader.readHeader(file));
    }

    @Test
    void rejectsUnknownMagic() throws Exception {
        Path file = tempDir.resolve("bad.blk");
        Files.write(file, new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertThrows(BlockReader.BlockCorruptException.class, () -> BlockReader.readHeader(file));
    }
}
