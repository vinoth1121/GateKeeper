package io.gatekeeper.resilience;

import java.time.Duration;

/**
 * Demo (simulated) fault-injection hook consulted by RedisGuard before every
 * real Redis call. The default bean is a no-op; the web layer's
 * ChaosFaultInjector flips modes for the chaos demo endpoints.
 */
public interface FaultInjector {

    /** Injected latency to apply before the real call, or null for none. */
    default Duration injectedLatency(String op) {
        return null;
    }

    /** Whether the next call for this op should fail as if Redis were unreachable. */
    default boolean injectFailure(String op) {
        return false;
    }
}
