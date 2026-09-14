package com.blocklog.storage;

import com.blocklog.model.LogRecord;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.CRC32;

public final class BlockReader {

    private static final LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();
    private static final int MIN_FILE_SIZE = 4 + 2 + 4 + 4; // magic + version + headerLen + headerCrc

    private BlockReader() {}

    public static BlockHeader readHeader(Path file) throws IOException, BlockCorruptException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < MIN_FILE_SIZE) {
                throw new BlockCorruptException("File too small: " + fileSize);
            }

            ByteBuffer prefixBuf = ByteBuffer.allocate(Math.min((int) fileSize, BlockHeader.MAX_HEADER_SIZE + 64));
            ch.read(prefixBuf);
            prefixBuf.flip();

            int magic = prefixBuf.getInt();
            if (magic != BlockHeader.MAGIC) {
                throw new BlockCorruptException("Bad magic: 0x" + Integer.toHexString(magic));
            }

            short version = prefixBuf.getShort();
            if (version != BlockHeader.CURRENT_VERSION) {
                throw new BlockCorruptException("Unknown version: " + version);
            }

            int headerLen = prefixBuf.getInt();
            if (headerLen < 0 || headerLen > BlockHeader.MAX_HEADER_SIZE) {
                throw new BlockCorruptException("Header length out of range: " + headerLen);
            }

            if (prefixBuf.remaining() < headerLen + 4) {
                throw new BlockCorruptException("File truncated: not enough data for header body + CRC");
            }

            byte[] headerBody = new byte[headerLen];
            prefixBuf.get(headerBody);

            int storedCrc = prefixBuf.getInt();
            CRC32 crc = new CRC32();
            crc.update(headerBody);
            if ((int) crc.getValue() != storedCrc) {
                throw new BlockCorruptException("Header CRC mismatch");
            }

            return parseHeaderBody(version, ByteBuffer.wrap(headerBody));
        }
    }

    public static List<LogRecord> readRecords(Path file, BlockHeader header) throws IOException, BlockCorruptException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int headerPrefixSize = 4 + 2 + 4;
            int bodyAndCrcSize = ch.size() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ch.size();

            ByteBuffer buf = ByteBuffer.allocate(bodyAndCrcSize);
            ch.read(buf);
            buf.flip();

            buf.position(headerPrefixSize);
            int headerLen = header.rawLength() >= 0 ? 0 : 0;
            // Skip past header body to get to compressed payload
            ByteBuffer headerBuf = ByteBuffer.allocate(4);
            buf.position(4 + 2); // after magic + version
            int hLen = buf.getInt();
            buf.position(4 + 2 + 4 + hLen + 4); // skip magic, ver, hdrLen, body, crc

            int compLen = header.compressedLength();
            if (buf.remaining() < compLen) {
                throw new BlockCorruptException("Not enough data for compressed payload");
            }

            byte[] compressed = new byte[compLen];
            buf.get(compressed);

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
            return records;
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
