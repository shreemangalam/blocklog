package com.blocklog.ingest;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-pool byte reservation. Threads try to acquire capacity for a whole
 * batch before enqueuing; consumers release capacity after publishing.
 * Reservations transfer with ownership: the queue holds bytes until the
 * consumer takes them, then the flush pipeline holds bytes until the block
 * is durably published.
 */
public final class ByteBudget {

    private final long capacity;
    private final AtomicLong inUse = new AtomicLong();

    public ByteBudget(long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    /** Reserve {@code bytes} atomically or return false and change nothing. */
    public boolean tryAcquire(long bytes) {
        while (true) {
            long current = inUse.get();
            long next = current + bytes;
            if (next > capacity) return false;
            if (inUse.compareAndSet(current, next)) return true;
        }
    }

    public void release(long bytes) {
        long remaining = inUse.addAndGet(-bytes);
        if (remaining < 0) {
            // Restore invariant: never over-release.
            inUse.addAndGet(bytes);
            throw new IllegalStateException("Byte budget under-released");
        }
    }

    public long inUse() { return inUse.get(); }

    public long capacity() { return capacity; }

    public double usagePct() {
        return (inUse.get() * 100.0) / capacity;
    }
}
