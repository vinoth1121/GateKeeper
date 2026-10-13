package io.gatekeeper.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.gatekeeper.resilience.FaultInjector;

/**
 * Demo (clearly simulated) Redis fault injector. The ChaosController flips
 * its mode; the RedisGuard consults it on every guarded Redis operation.
 *
 * - NORMAL: no injected faults (defaults of the FaultInjector interface).
 * - LATENCY: every Redis op first sleeps the configured latency.
 * - OUTAGE: every Redis op fails as if Redis were unreachable.
 */
public class ChaosFaultInjector implements FaultInjector {

    public enum Mode { NORMAL, LATENCY, OUTAGE }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
    private volatile long latencyMs = 0;

    public void configure(String modeName, Long latencyMs) {
        Mode parsed;
        try {
            parsed = Mode.valueOf(modeName == null ? "" : modeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            parsed = Mode.NORMAL;
        }
        this.mode.set(parsed);
        if (latencyMs != null) {
            this.latencyMs = Math.max(0L, latencyMs);
        }
        if (parsed != Mode.LATENCY) {
            this.latencyMs = 0L;
        }
    }

    public Mode mode() {
        return mode.get();
    }

    public long latencyMs() {
        return latencyMs;
    }

    public boolean faultActive() {
        return mode.get() != Mode.NORMAL;
    }

    public Map<String, Object> describe() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mode", mode.get().name().toLowerCase(Locale.ROOT));
        state.put("latencyMs", latencyMs);
        return state;
    }

    @Override
    public Duration injectedLatency(String op) {
        return mode.get() == Mode.LATENCY ? Duration.ofMillis(Math.max(1L, latencyMs)) : null;
    }

    @Override
    public boolean injectFailure(String op) {
        return mode.get() == Mode.OUTAGE;
    }
}
