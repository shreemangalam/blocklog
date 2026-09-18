package com.blocklog.ingest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A byte-reservation pool with both a global cap and a per-tenant cap.
 *
 * The global cap is the total headroom across all tenants; the per-tenant
 * cap is the slice a single tenant is allowed to hold before being rejected.
 * A reservation succeeds only when both caps can accommodate the bytes; if
 * the global check fails after the tenant has already reserved, the tenant
 * reservation is rolled back atomically.
 *
 * The tenant tracking map grows with the number of tenants observed. On
 * this single-node prototype that's bounded by the operator's own tenant
 * count; in a real multi-tenant system it needs a cleanup path when a
 * tenant's usage drops to zero and stays there. Not addressed in this
 * sprint.
 *
 * When {@code perTenantCapacity == globalCapacity}, this behaves identically
 * to a single global {@link ByteBudget} — the per-tenant slice is the whole
 * pool and one tenant can fill it.
 */
public final class TenantByteBudget {

    private final long globalCapacity;
    private final long perTenantCapacity;
    private final AtomicLong globalInUse = new AtomicLong();
    private final Map<String, AtomicLong> perTenantInUse = new ConcurrentHashMap<>();

    public TenantByteBudget(long globalCapacity, long perTenantCapacity) {
        if (globalCapacity <= 0) throw new IllegalArgumentException("globalCapacity must be > 0");
        if (perTenantCapacity <= 0) throw new IllegalArgumentException("perTenantCapacity must be > 0");
        if (perTenantCapacity > globalCapacity) {
            throw new IllegalArgumentException(
                    "perTenantCapacity (" + perTenantCapacity + ") must be <= globalCapacity (" + globalCapacity + ")");
        }
        this.globalCapacity = globalCapacity;
        this.perTenantCapacity = perTenantCapacity;
    }

    /**
     * Reserve {@code bytes} for {@code tenant} atomically against both the
     * per-tenant and global budgets. Returns false and changes nothing when
     * either cap would be exceeded.
     */
    public boolean tryAcquire(String tenant, long bytes) {
        AtomicLong tenantCounter = perTenantInUse.computeIfAbsent(tenant, k -> new AtomicLong());

        // Reserve tenant slice first — cheaper to roll back if global fails.
        while (true) {
            long current = tenantCounter.get();
            long next = current + bytes;
            if (next > perTenantCapacity) return false;
            if (tenantCounter.compareAndSet(current, next)) break;
        }

        // Try to reserve on the global pool.
        while (true) {
            long current = globalInUse.get();
            long next = current + bytes;
            if (next > globalCapacity) {
                // Roll back the tenant reservation and refuse.
                tenantCounter.addAndGet(-bytes);
                return false;
            }
            if (globalInUse.compareAndSet(current, next)) return true;
        }
    }

    /** Release {@code bytes} previously reserved by {@code tenant}. */
    public void release(String tenant, long bytes) {
        long globalRemaining = globalInUse.addAndGet(-bytes);
        if (globalRemaining < 0) {
            globalInUse.addAndGet(bytes);
            throw new IllegalStateException("Global byte budget under-released");
        }
        AtomicLong tenantCounter = perTenantInUse.get(tenant);
        if (tenantCounter != null) {
            long tenantRemaining = tenantCounter.addAndGet(-bytes);
            if (tenantRemaining < 0) {
                tenantCounter.addAndGet(bytes);
                throw new IllegalStateException("Tenant byte budget under-released for " + tenant);
            }
        }
    }

    public long globalInUse() { return globalInUse.get(); }
    public long globalCapacity() { return globalCapacity; }
    public long perTenantCapacity() { return perTenantCapacity; }

    public long tenantInUse(String tenant) {
        AtomicLong counter = perTenantInUse.get(tenant);
        return counter == null ? 0 : counter.get();
    }

    public double globalUsagePct() {
        return (globalInUse.get() * 100.0) / globalCapacity;
    }
}
