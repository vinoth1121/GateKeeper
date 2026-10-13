package io.gatekeeper.limiter;

import io.gatekeeper.metrics.GateKeeperMeters;

import java.util.List;

/**
 * Redis-backed rate limiter. Keys use hash tags for Cluster safety:
 * gk:{t:<tenantId>}:k:<keyHash>:r:<ruleId> (sliding window adds a ":c" counter key
 * in the same hash tag). Token bucket math is integer-only milli-tokens.
 */
public final class RedisRateLimiterService implements RateLimiterService {

    static final long MAX_TTL_SECONDS = 3600;

    private final LuaScriptManager scripts;
    private final GateKeeperMeters meters;

    public RedisRateLimiterService(LuaScriptManager scripts, GateKeeperMeters meters) {
        this.scripts = scripts;
        this.meters = meters;
    }

    public static String bucketKey(String tenantId, String keyHash, String ruleId) {
        return "gk:{t:" + tenantId + "}:k:" + keyHash + ":r:" + ruleId;
    }

    @Override
    public LimiterDecision check(String tenantId, String tier, String keyHash, LimitSpec spec) {
        String ruleId = spec.ruleId();
        try {
            List<Object> tuple;
            if (spec.algorithm() == LimitSpec.Algorithm.TOKEN_BUCKET) {
                tuple = scripts.eval(
                        LuaScriptManager.TOKEN_BUCKET,
                        List.of(bucketKey(tenantId, keyHash, ruleId)),
                        List.of(
                                Long.toString(spec.capacity() * 1000L),
                                Long.toString(Math.round(spec.refillPerSec() * 1000.0)),
                                Long.toString(spec.cost() * 1000L),
                                Long.toString(MAX_TTL_SECONDS)));
            } else {
                String zset = bucketKey(tenantId, keyHash, ruleId);
                tuple = scripts.eval(
                        LuaScriptManager.SLIDING_WINDOW_LOG,
                        List.of(zset, zset + ":c"),
                        List.of(
                                Long.toString(spec.max()),
                                Long.toString(spec.windowMs()),
                                Long.toString(spec.cost())));
            }
            boolean allowed = ((Number) tuple.get(0)).longValue() == 1L;
            int remaining = ((Number) tuple.get(1)).intValue();
            long retryAfterMs = ((Number) tuple.get(2)).longValue();
            long resetEpochMs = ((Number) tuple.get(3)).longValue();
            String reason = allowed ? "ok" : "limit_exceeded";
            record(tier, ruleId, allowed, reason);
            return new LimiterDecision(allowed, remaining, retryAfterMs, resetEpochMs,
                    LimiterDecision.DecisionSource.REDIS, reason);
        } catch (RuntimeException e) {
            // Redis unavailable: strict rules fail closed, others fail open.
            boolean allowed = !spec.strict();
            record(tier, ruleId, allowed, "redis_error");
            return new LimiterDecision(allowed, 0, 0, 0,
                    LimiterDecision.DecisionSource.DEGRADED, "redis_error");
        }
    }

    private void record(String tier, String ruleId, boolean allowed, String reason) {
        meters.decisionsTotal(tier, ruleId, allowed ? "allowed" : "denied", reason).increment();
    }
}
