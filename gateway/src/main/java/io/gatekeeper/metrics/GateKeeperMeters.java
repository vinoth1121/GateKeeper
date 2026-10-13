package io.gatekeeper.metrics;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Thin helper over MeterRegistry. Null-registry safe: a registry-less instance
 * routes all meters into an inert composite, so plain non-Spring tests work.
 */
public final class GateKeeperMeters {

    public static final String REDIS_CALL_TIMER = "gatekeeper.redis.call.seconds";
    public static final String REDIS_CALLS_TOTAL = "gatekeeper.redis.calls.total";
    public static final String DECISIONS_TOTAL = "gatekeeper.decisions.total";

    private final MeterRegistry registry;

    public GateKeeperMeters(MeterRegistry registry) {
        this.registry = registry != null ? registry : new CompositeMeterRegistry();
    }

    public Timer redisTimer(String op) {
        return Timer.builder(REDIS_CALL_TIMER)
                .description("Redis limiter script call duration")
                .tag("op", op)
                .register(registry);
    }

    public Counter redisCallTotal(String op) {
        return Counter.builder(REDIS_CALLS_TOTAL)
                .description("Redis limiter script calls")
                .tag("op", op)
                .register(registry);
    }

    public Counter decisionsTotal(String tier, String rule, String decision, String reason) {
        return Counter.builder(DECISIONS_TOTAL)
                .description("Rate limit decisions")
                .tag("tier", tier)
                .tag("rule", rule)
                .tag("decision", decision)
                .tag("reason", reason)
                .register(registry);
    }
}
