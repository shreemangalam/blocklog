package com.blocklog.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only memory-mapped view of one published block file. Each reader
 * takes an independent {@link ByteBuffer#duplicate() duplicate} view so
 * concurrent scans do not share position/limit. The mapping lifetime is
 * tied to buffer garbage collection; closing the underlying channel does
 * not explicitly unmap it, so we never mutate or delete mapped files.
 */
public final class BlockMapping implements AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private final MappedByteBuffer mapped;
    private final long size;

    private BlockMapping(Path file, FileChannel channel, MappedByteBuffer mapped, long size) {
        this.file = file;
        this.channel = channel;
        this.mapped = mapped;
        this.size = size;
    }

    public static BlockMapping open(Path file) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            long size = ch.size();
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return new BlockMapping(file, ch, mapped, size);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    /** Independent view — safe to read concurrently from multiple threads. */
    public ByteBuffer duplicate() {
        return mapped.duplicate().order(mapped.order());
    }

    public long size() { return size; }
    public Path file() { return file; }

    @Override
    public void close() throws IOException {
        // Note: closing the channel releases the file descriptor, but on JVMs
        // the mapping is only unmapped when the MappedByteBuffer is GCd. We
        // never truncate or delete published block files during a live run.
        channel.close();
    }
}
