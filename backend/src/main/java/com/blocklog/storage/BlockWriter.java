package com.blocklog.storage;

import com.blocklog.model.LogRecord;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.CRC32;

public final class BlockWriter {

    private static final LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();

    private BlockWriter() {}

    public static Path writeBlock(Path dataDir, String tenantId, List<LogRecord> records, String blockId) throws IOException {
        byte[] rawPayload = encodeRecords(records);
        byte[] compressed = compressor.compress(rawPayload);

        CRC32 payloadCrc = new CRC32();
        payloadCrc.update(compressed);
        int payloadCrcValue = (int) payloadCrc.getValue();

        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        Map<String, Set<String>> tagSummary = new LinkedHashMap<>();
        boolean tagsSaturated = false;
        int totalTagPairs = 0;

        for (LogRecord record : records) {
            if (record.timestamp() < minTs) minTs = record.timestamp();
            if (record.timestamp() > maxTs) maxTs = record.timestamp();
            if (!tagsSaturated) {
                for (var entry : record.tags().entrySet()) {
                    boolean added = tagSummary.computeIfAbsent(
                            entry.getKey(), k -> new LinkedHashSet<>()).add(entry.getValue());
                    if (added) {
                        totalTagPairs++;
                        if (totalTagPairs > BlockHeader.MAX_TAG_SUMMARY_PAIRS) {
                            tagsSaturated = true;
                            tagSummary.clear();
                            break;
                        }
                    }
                }
            }
        }

        byte[] headerBytes = encodeHeader(tenantId, records.size(), minTs, maxTs,
                rawPayload.length, compressed.length, payloadCrcValue,
                tagsSaturated ? Map.of() : tagSummary, tagsSaturated);

        Path tempFile = dataDir.resolve(".tmp." + blockId + ".blk");
        Path finalFile = dataDir.resolve(blockId + ".blk");

        try (FileChannel ch = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.allocate(headerBytes.length + compressed.length);
            buf.put(headerBytes);
            buf.put(compressed);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }

        Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        return finalFile;
    }

    private static byte[] encodeRecords(List<LogRecord> records) {
        var baos = new ByteArrayOutputStream(4096);
        for (LogRecord record : records) {
            byte[] encoded = RecordCodec.encode(record);
            baos.writeBytes(encoded);
        }
        return baos.toByteArray();
    }

    private static byte[] encodeHeader(String tenantId, int recordCount,
                                        long minTs, long maxTs,
                                        int rawLen, int compLen, int payloadCrc,
                                        Map<String, Set<String>> tagSummary, boolean tagsSaturated) {
        try {
            var baos = new ByteArrayOutputStream(512);
            var out = new DataOutputStream(baos);

            out.writeInt(BlockHeader.MAGIC);
            out.writeShort(BlockHeader.CURRENT_VERSION);

            var headerBody = new ByteArrayOutputStream(256);
            var hout = new DataOutputStream(headerBody);

            byte[] tenantBytes = tenantId.getBytes(StandardCharsets.UTF_8);
            hout.writeShort(tenantBytes.length);
            hout.write(tenantBytes);

            hout.writeInt(recordCount);
            hout.writeLong(minTs);
            hout.writeLong(maxTs);
            hout.writeInt(rawLen);
            hout.writeInt(compLen);
            hout.writeInt(payloadCrc);

            hout.writeBoolean(tagsSaturated);
            if (!tagsSaturated) {
                hout.writeShort(tagSummary.size());
                for (var entry : tagSummary.entrySet()) {
                    writeStr(hout, entry.getKey());
                    hout.writeShort(entry.getValue().size());
                    for (String val : entry.getValue()) {
                        writeStr(hout, val);
                    }
                }
            } else {
                hout.writeShort(0);
            }
            hout.flush();

            byte[] bodyBytes = headerBody.toByteArray();

            CRC32 headerCrc = new CRC32();
            headerCrc.update(bodyBytes);

            out.writeInt(bodyBytes.length);
            out.write(bodyBytes);
            out.writeInt((int) headerCrc.getValue());

            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Header encoding failed", e);
        }
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }
}
