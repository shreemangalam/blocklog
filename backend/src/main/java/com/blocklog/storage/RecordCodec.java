package com.blocklog.storage;

import com.blocklog.model.LogRecord;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RecordCodec {

    private RecordCodec() {}

    public static byte[] encode(LogRecord record) {
        try {
            var baos = new ByteArrayOutputStream(256);
            var out = new DataOutputStream(baos);

            out.writeLong(record.timestamp());

            Map<String, String> tags = record.tags();
            out.writeShort(tags.size());
            for (var entry : tags.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }

            byte[] msgBytes = record.message().getBytes(StandardCharsets.UTF_8);
            out.writeInt(msgBytes.length);
            out.write(msgBytes);

            out.flush();
            byte[] payload = baos.toByteArray();

            var result = new ByteArrayOutputStream(payload.length + 4);
            var wrapper = new DataOutputStream(result);
            wrapper.writeInt(payload.length);
            wrapper.write(payload);
            wrapper.flush();

            return result.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Record encoding failed", e);
        }
    }

    public static LogRecord decode(ByteBuffer buf) {
        int length = buf.getInt();
        int startPos = buf.position();

        long timestamp = buf.getLong();

        int tagCount = buf.getShort() & 0xFFFF;
        Map<String, String> tags = new LinkedHashMap<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            String key = readString(buf);
            String value = readString(buf);
            tags.put(key, value);
        }

        int msgLen = buf.getInt();
        byte[] msgBytes = new byte[msgLen];
        buf.get(msgBytes);
        String message = new String(msgBytes, StandardCharsets.UTF_8);

        int consumed = buf.position() - startPos;
        if (consumed != length) {
            throw new IllegalStateException("Record length mismatch: declared " + length + ", consumed " + consumed);
        }

        return new LogRecord(timestamp, Map.copyOf(tags), message);
    }

    public static int encodedSize(LogRecord record) {
        int size = 4 + 8 + 2;
        for (var entry : record.tags().entrySet()) {
            size += 2 + entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            size += 2 + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        size += 4 + record.message().getBytes(StandardCharsets.UTF_8).length;
        return size;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
