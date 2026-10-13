package io.gatekeeper.lease;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LimitSpec;
import io.gatekeeper.limiter.LimiterDecision;
import io.gatekeeper.limiter.RateLimiterService;
import io.gatekeeper.limiter.RedisRateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.gatekeeper.resilience.RedisGuard;

/**
 * RateLimiterService with two-tier local token leasing.
 *
 * strict rules -> delegated to the wrapped RedisRateLimiterService through the
 * RedisGuard (fail-closed: breaker open / timeout -> denied DEGRADED).
 * non-strict rules -> served from LocalLeaseManager leases (LOCAL source for
 * allows). While the breaker is OPEN the local Java milli-token backstop
 * serves requests at refill/max(1,liveNodeCount) (fail-open share; starts
 * empty so the global bucket bound holds) -> DEGRADED decisions; a probe
 * keeps attempting refills so the breaker can recover to HALF_OPEN/CLOSED.
 */
public final class LeasingRateLimiterService implements RateLimiterService {

    private final RateLimiterService strictDelegate;
    private final LocalLeaseManager leases;
    private final RedisGuard guard;
    private final GateKeeperMeters meters;
    private final GateKeeperProperties.Lease props;
    private final ConcurrentHashMap<String, BackstopState> backstops = new ConcurrentHashMap<>();

    public LeasingRateLimiterService(RateLimiterService strictDelegate, LocalLeaseManager leases,
            RedisGuard guard, GateKeeperMeters meters, GateKeeperProperties.Lease props) {
        this.strictDelegate = strictDelegate;
        this.leases = leases;
        this.guard = guard;
        this.meters = meters;
        this.props = props;
    }

    @Override
    public LimiterDecision check(String tenantId, String tier, String keyHash, LimitSpec spec) {
        boolean strictBypass = props == null || props.strictBypass();
        if (spec.strict() && strictBypass) {
            return guard.call("strict_check",
                    () -> strictDelegate.check(tenantId, tier, keyHash, spec),
                    () -> failClosed(tier, spec));
        }
        if (guard.state() == CircuitBreaker.State.OPEN) {
            // Keep probing refills so the breaker can transition to HALF_OPEN and recover.
            leases.probe(tenantId, keyHash, spec);
            return backstop(tenantId, tier, keyHash, spec);
        }
        LocalLeaseManager.LeaseOutcome outcome = leases.tryConsume(tenantId, keyHash, spec);
        boolean allowed = outcome.allowed();
        String reason = allowed ? "ok" : "limit_exceeded";
        record(tier, spec, allowed, reason);
        return new LimiterDecision(allowed, (int) outcome.remaining(), outcome.retryAfterMs(),
                outcome.resetEpochMs(), LimiterDecision.DecisionSource.LOCAL, reason);
    }

    private LimiterDecision failClosed(String tier, LimitSpec spec) {
        record(tier, spec, false, "fail_closed");
        return new LimiterDecision(false, 0, 0, 0,
                LimiterDecision.DecisionSource.DEGRADED, "fail_closed");
    }

    /** Local Java milli-token backstop while the breaker is OPEN (fail-open share). */
    private LimiterDecision backstop(String tenantId, String tier, String keyHash, LimitSpec spec) {
        String key = RedisRateLimiterService.bucketKey(tenantId, keyHash, spec.ruleId());
        int nodes = Math.max(1, guard.liveNodeCount());
        long capacityMilli = Math.max(spec.cost(), (spec.capacity() + nodes - 1) / nodes) * 1000L;
        long refillMilli = Math.round(spec.refillPerSec() * 1000.0 / nodes);
        long costMilli = spec.cost() * 1000L;
        BackstopState st = backstops.computeIfAbsent(key, k -> new BackstopState());
        long now = System.currentTimeMillis();
        long allowedAt = 0;
        long tokensAfter;
        synchronized (st) {
            long elapsed = Math.max(0, now - st.lastRefillMs.get());
            long tokens = st.tokens.get();
            if (refillMilli > 0) {
                tokens = Math.min(capacityMilli, tokens + elapsed * refillMilli / 1000);
            }
            boolean allowed = tokens >= costMilli;
            if (allowed) {
                tokens -= costMilli;
                allowedAt = 1;
            }
            st.tokens.set(tokens);
            st.lastRefillMs.set(now);
            long retryAfter = 0;
            if (allowedAt == 0 && refillMilli > 0) {
                retryAfter = Math.max(1, (long) Math.ceil((costMilli - tokens) * 1000.0 / refillMilli));
            }
            record(tier, spec, allowedAt == 1, "local_backstop");
            return new LimiterDecision(allowedAt == 1, (int) (tokens / 1000), retryAfter,
                    now + retryAfter, LimiterDecision.DecisionSource.DEGRADED, "local_backstop");
        }
    }

    private void record(String tier, LimitSpec spec, boolean allowed, String reason) {
        meters.decisionsTotal(tier, spec.ruleId(), allowed ? "allowed" : "denied", reason).increment();
    }

    static final class BackstopState {
        final AtomicLong tokens = new AtomicLong();
        final AtomicLong lastRefillMs = new AtomicLong(System.currentTimeMillis());
    }
}
