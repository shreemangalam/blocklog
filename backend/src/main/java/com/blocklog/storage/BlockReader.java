package com.blocklog.storage;

import com.blocklog.model.LogRecord;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;

public final class BlockReader {

    private static final LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();

    /** magic(4) + version(2) + headerLen(4) + minimum header body + headerCrc(4). */
    private static final int MIN_FILE_SIZE = 4 + 2 + 4 + 4;

    private BlockReader() {}

    /**
     * Read only the header + validate the header CRC. Used at restart-time
     * discovery, before the payload mmap is opened. Reads a bounded prefix
     * of the file rather than mapping the whole thing, so corrupt/oversized
     * files cannot force a large mapping.
     */
    public static BlockHeader readHeader(Path file) throws IOException, BlockCorruptException {
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < MIN_FILE_SIZE) {
                throw new BlockCorruptException("File too small: " + fileSize);
            }

            int prefixLen = (int) Math.min(fileSize, BlockHeader.MAX_HEADER_SIZE + 64);
            ByteBuffer buf = ByteBuffer.allocate(prefixLen);
            ch.read(buf);
            buf.flip();

            return parseHeaderFromPrefix(buf, fileSize);
        }
    }

    /** Parse the header from a buffer positioned at file offset 0. */
    public static BlockHeader parseHeaderFromMapping(BlockMapping mapping) throws BlockCorruptException {
        ByteBuffer buf = mapping.duplicate();
        return parseHeaderFromPrefix(buf, mapping.size());
    }

    private static BlockHeader parseHeaderFromPrefix(ByteBuffer buf, long fileSize) throws BlockCorruptException {
        int magic = buf.getInt();
        if (magic != BlockHeader.MAGIC) {
            throw new BlockCorruptException("Bad magic: 0x" + Integer.toHexString(magic));
        }

        short version = buf.getShort();
        if (version != BlockHeader.CURRENT_VERSION) {
            throw new BlockCorruptException("Unknown version: " + version);
        }

        int headerLen = buf.getInt();
        if (headerLen < 0 || headerLen > BlockHeader.MAX_HEADER_SIZE) {
            throw new BlockCorruptException("Header length out of range: " + headerLen);
        }
        if (buf.remaining() < headerLen + 4) {
            throw new BlockCorruptException("File truncated: not enough data for header body + CRC");
        }

        byte[] headerBody = new byte[headerLen];
        buf.get(headerBody);

        int storedCrc = buf.getInt();
        CRC32 crc = new CRC32();
        crc.update(headerBody);
        if ((int) crc.getValue() != storedCrc) {
            throw new BlockCorruptException("Header CRC mismatch");
        }

        BlockHeader header = parseHeaderBody(version, ByteBuffer.wrap(headerBody));

        // Exact file-length consistency check.
        long expectedFileSize = (long) (4 + 2 + 4) + headerLen + 4 + header.compressedLength();
        if (fileSize != expectedFileSize) {
            throw new BlockCorruptException(
                    "File length mismatch: expected " + expectedFileSize + ", found " + fileSize);
        }
        return header;
    }

    /**
     * Read + validate + decompress the payload from an already-open mmap.
     * Bounds and header integrity must already be verified by the caller
     * (see {@link #parseHeaderFromMapping}).
     */
    public static List<LogRecord> readRecords(BlockMapping mapping, BlockHeader header)
            throws BlockCorruptException {
        ByteBuffer view = mapping.duplicate();

        int payloadOffset = 4 + 2 + 4 + view.getInt(4 + 2) + 4; // magic+ver+len + headerBody + hdrCrc
        view.position(payloadOffset);

        int compLen = header.compressedLength();
        if (view.remaining() < compLen) {
            throw new BlockCorruptException("Not enough data for compressed payload");
        }

        byte[] compressed = new byte[compLen];
        view.get(compressed);

        CRC32 payloadCrc = new CRC32();
        payloadCrc.update(compressed);
        if ((int) payloadCrc.getValue() != header.payloadCrc32()) {
            throw new BlockCorruptException("Payload CRC mismatch");
        }

        byte[] raw = new byte[header.rawLength()];
        decompressor.decompress(compressed, 0, raw, 0, header.rawLength());

        ByteBuffer recordBuf = ByteBuffer.wrap(raw);
        List<LogRecord> records = new ArrayList<>(header.recordCount());
        for (int i = 0; i < header.recordCount(); i++) {
            records.add(RecordCodec.decode(recordBuf));
        }
        if (recordBuf.hasRemaining()) {
            throw new BlockCorruptException("Trailing bytes after decoded records");
        }
        return records;
    }

    /**
     * Convenience overload: open + parse + read + close mapping in one call.
     * Used by tests and any one-shot reader that doesn't need to keep the
     * mapping cached.
     */
    public static List<LogRecord> readRecords(Path file, BlockHeader header)
            throws IOException, BlockCorruptException {
        try (BlockMapping mapping = BlockMapping.open(file)) {
            return readRecords(mapping, header);
        }
    }

    private static BlockHeader parseHeaderBody(short version, ByteBuffer buf) {
        int tenantLen = buf.getShort() & 0xFFFF;
        byte[] tenantBytes = new byte[tenantLen];
        buf.get(tenantBytes);
        String tenantId = new String(tenantBytes, StandardCharsets.UTF_8);

        int recordCount = buf.getInt();
        long minTs = buf.getLong();
        long maxTs = buf.getLong();
        int rawLen = buf.getInt();
        int compLen = buf.getInt();
        int payloadCrc = buf.getInt();

        boolean tagsSaturated = buf.get() != 0;
        int tagKeyCount = buf.getShort() & 0xFFFF;
        Map<String, Set<String>> tagSummary = new LinkedHashMap<>(tagKeyCount);
        for (int i = 0; i < tagKeyCount; i++) {
            String key = readStr(buf);
            int valCount = buf.getShort() & 0xFFFF;
            Set<String> vals = new LinkedHashSet<>(valCount);
            for (int j = 0; j < valCount; j++) {
                vals.add(readStr(buf));
            }
            tagSummary.put(key, vals);
        }

        return new BlockHeader(version, tenantId, recordCount, minTs, maxTs,
                rawLen, compLen, payloadCrc, Map.copyOf(tagSummary), tagsSaturated);
    }

    private static String readStr(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static class BlockCorruptException extends Exception {
        public BlockCorruptException(String message) {
            super(message);
        }
    }
}
