package io.gatekeeper.lease;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LimitSpec;
import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RedisRateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.gatekeeper.resilience.LuaScripts;
import io.gatekeeper.resilience.RedisGuard;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Two-tier local leasing: each node holds milli-token leases per bucket.
 * Local hits are pure Java; low-water (20% of lastLeaseSize) triggers ONE
 * async single-flight refill on a virtual thread via lease.lua through the
 * RedisGuard; an empty lease waits at most 50ms for an in-flight refill.
 *
 * A sweeper (virtual thread every leaseTtlMs/2) and the shutdown hook return
 * leftover local tokens to the bucket via return_tokens.lua, capped at
 * capacity. Tokens still held when the process crashes are simply lost:
 * acceptable under-use, never over-use.
 */
public final class LocalLeaseManager {

    public static final String LEASE = "lease";
    public static final String RETURN_TOKENS = "return_tokens";
    static final long MAX_TTL_SECONDS = 3600;
    static final long EMPTY_WAIT_MS = 50;

    private final RedisGuard guard;
    private final LuaScriptManager scripts;
    private final GateKeeperProperties.Lease props;
    private final GateKeeperMeters meters;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, LeaseState> states = new ConcurrentHashMap<>();
    private final ExecutorService refills = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong refillTotal = new AtomicLong();
    private final AtomicLong expiredTokensMilli = new AtomicLong();
    private final AtomicLong localHits = new AtomicLong();
    private final AtomicLong localChecks = new AtomicLong();
    private final Thread sweeper;
    private volatile boolean running = true;

    public LocalLeaseManager(RedisGuard guard, LuaScriptManager scripts,
            GateKeeperProperties.Lease props, GateKeeperMeters meters, MeterRegistry registry) {
        this.guard = guard;
        this.scripts = scripts;
        this.props = props;
        this.meters = meters;
        this.registry = registry != null ? registry : new CompositeMeterRegistry();
        scripts.registerScript(LEASE, LuaScripts.read("/lua/lease.lua"));
        scripts.registerScript(RETURN_TOKENS, LuaScripts.read("/lua/return_tokens.lua"));
        Gauge.builder("gatekeeper.local.hit.ratio", this, LocalLeaseManager::hitRatio)
                .description("Local lease hit ratio for lease-served decisions")
                .register(this.registry);
        FunctionCounter.builder("gatekeeper.lease.refill.total", refillTotal, AtomicLong::get)
                .description("Local lease refill attempts")
                .register(this.registry);
        FunctionCounter.builder("gatekeeper.lease.expired.tokens.total", expiredTokensMilli, AtomicLong::get)
                .description("Lease milli-tokens returned to Redis on expiry/shutdown")
                .register(this.registry);
        sweeper = Thread.ofVirtual().name("gk-lease-sweeper").start(this::sweepLoop);
    }

    static final class LeaseState {
        final AtomicLong tokens = new AtomicLong();
        volatile long expiryMs;
        final AtomicBoolean refillInFlight = new AtomicBoolean();
        volatile int lastLeaseSize;
        volatile long capacityMilli;
        volatile long refillMilli;
    }

    /** Result of consuming cost from the local lease (milli-token bookkeeping). */
    public record LeaseOutcome(boolean allowed, long remaining, long retryAfterMs, long resetEpochMs) {
    }

    public LeaseOutcome tryConsume(String tenantId, String keyHash, LimitSpec spec) {
        String key = RedisRateLimiterService.bucketKey(tenantId, keyHash, spec.ruleId());
        int leaseSize = leaseSize(spec);
        long costMilli = spec.cost() * 1000L;
        LeaseState st = stateFor(key, spec);
        long deadline = System.currentTimeMillis() + EMPTY_WAIT_MS;
        localChecks.incrementAndGet();
        while (true) {
            long tokens = st.tokens.get();
            if (tokens >= costMilli && st.tokens.compareAndSet(tokens, tokens - costMilli)) {
                long remainingMilli = tokens - costMilli;
                st.expiryMs = System.currentTimeMillis() + props.leaseTtlMs();
                maybeRefill(st, key, spec, leaseSize, remainingMilli);
                localHits.incrementAndGet();
                long refillMilli = st.refillMilli;
                long resetMs = System.currentTimeMillis()
                        + (refillMilli > 0 ? (long) Math.ceil((st.capacityMilli - remainingMilli) * 1000.0 / refillMilli) : 0);
                return new LeaseOutcome(true, remainingMilli / 1000, 0, resetMs);
            }
            if (st.refillInFlight.compareAndSet(false, true)) {
                spawnRefill(st, key, spec, leaseSize);
            }
            if (System.currentTimeMillis() >= deadline) {
                long have = st.tokens.get();
                return new LeaseOutcome(false, have / 1000,
                        retryAfterMs(st.refillMilli, costMilli - have), System.currentTimeMillis());
            }
            sleepQuietly(5);
        }
    }

    /** Fire-and-forget refill attempt (used to probe a closed breaker during OPEN). */
    public void probe(String tenantId, String keyHash, LimitSpec spec) {
        String key = RedisRateLimiterService.bucketKey(tenantId, keyHash, spec.ruleId());
        LeaseState st = stateFor(key, spec);
        if (st.refillInFlight.compareAndSet(false, true)) {
            spawnRefill(st, key, spec, leaseSize(spec));
        }
    }

    public double hitRatio() {
        long checks = localChecks.get();
        return checks == 0 ? 0.0 : (double) localHits.get() / checks;
    }

    public void shutdown() {
        running = false;
        sweeper.interrupt();
        for (var entry : states.entrySet()) {
            LeaseState st = entry.getValue();
            if (st.refillInFlight.get()) {
                continue;
            }
            long leftover = st.tokens.getAndSet(0);
            if (leftover > 0) {
                expiredTokensMilli.addAndGet(leftover);
                returnLeftover(entry.getKey(), st, leftover);
            }
        }
        refills.shutdown();
    }

    private LeaseState stateFor(String key, LimitSpec spec) {
        LeaseState st = states.computeIfAbsent(key, k -> new LeaseState());
        st.capacityMilli = spec.capacity() * 1000L;
        st.refillMilli = Math.round(spec.refillPerSec() * 1000.0);
        return st;
    }

    /** Low-water: remaining <= lowWaterMark * lastLeaseSize triggers one async single-flight refill. */
    private void maybeRefill(LeaseState st, String key, LimitSpec spec, int leaseSize, long remainingMilli) {
        long lowWaterMilli = (long) (props.lowWaterMark() * st.lastLeaseSize * 1000.0);
        if (remainingMilli <= lowWaterMilli && st.refillInFlight.compareAndSet(false, true)) {
            spawnRefill(st, key, spec, leaseSize);
        }
    }

    private void spawnRefill(LeaseState st, String key, LimitSpec spec, int leaseSize) {
        refillTotal.incrementAndGet();
        try {
            refills.submit(() -> {
                try {
                    doRefill(st, key, spec, leaseSize);
                } finally {
                    st.refillInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            st.refillInFlight.set(false);
        }
    }

    private void doRefill(LeaseState st, String key, LimitSpec spec, int leaseSize) {
        List<Object> result = guard.call(LEASE, () -> scripts.eval(LEASE,
                List.of(key),
                List.of(
                        Long.toString(st.capacityMilli),
                        Long.toString(st.refillMilli),
                        Long.toString(leaseSize * 1000L),
                        Long.toString(MAX_TTL_SECONDS))),
                () -> List.of(0L, 0L, 1000L, 0L));
        long granted = ((Number) result.get(0)).longValue();
        if (granted > 0) {
            st.lastLeaseSize = leaseSize;
            st.tokens.addAndGet(granted);
            st.expiryMs = System.currentTimeMillis() + props.leaseTtlMs();
        }
    }

    private void sweepLoop() {
        long interval = Math.max(100, props.leaseTtlMs() / 2);
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            for (var entry : states.entrySet()) {
                LeaseState st = entry.getValue();
                if (now <= st.expiryMs || st.refillInFlight.get()) {
                    continue;
                }
                long leftover = st.tokens.getAndSet(0);
                if (leftover > 0) {
                    expiredTokensMilli.addAndGet(leftover);
                    returnLeftover(entry.getKey(), st, leftover);
                }
            }
        }
    }

    private void returnLeftover(String key, LeaseState st, long leftoverMilli) {
        if (st.capacityMilli <= 0) {
            return;
        }
        // If Redis is unavailable the leftover is lost: acceptable under-use, never over-use.
        guard.call(RETURN_TOKENS, () -> scripts.eval(RETURN_TOKENS,
                List.of(key),
                List.of(
                        Long.toString(st.capacityMilli),
                        Long.toString(leftoverMilli),
                        Long.toString(st.refillMilli),
                        Long.toString(MAX_TTL_SECONDS))),
                () -> null);
    }

    int leaseSize(LimitSpec spec) {
        double lease = Math.ceil(spec.refillPerSec() * props.leaseWindowMs() / 1000.0);
        int size = (int) Math.min(props.maxLease(), Math.max(1.0, lease));
        if (spec.capacity() < 20) {
            size = Math.min(size, 2);
        }
        return Math.max(1, size);
    }

    private static long retryAfterMs(long refillMilli, long deficitMilli) {
        if (refillMilli <= 0 || deficitMilli <= 0) {
            return 1000;
        }
        return Math.max(1, (long) Math.ceil(deficitMilli * 1000.0 / refillMilli));
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
