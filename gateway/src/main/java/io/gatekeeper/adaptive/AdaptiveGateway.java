package io.gatekeeper.adaptive;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import io.gatekeeper.config.GateKeeperProperties;

/**
 * Facade over the per-route Gradient2 limiter registry and the priority
 * shedder. The web layer resolves a request's priority (applying any trusted
 * X-GateKeeper-Priority override itself) and calls {@link #admit}; the
 * returned {@link ShedDecision} tells it whether to proceed or to answer with
 * a shed status and Retry-After.
 *
 * Null-registry safe like {@code GateKeeperMeters}: a null registry (plain unit
 * tests) routes gauges into an inert composite.
 */
public final class AdaptiveGateway {

    public static final String LIMIT_GAUGE = "gatekeeper_adaptive_limit";
    public static final String INFLIGHT_GAUGE = "gatekeeper_inflight";

    private final GateKeeperProperties.Adaptive config;
    private final PriorityShedder shedder;
    private final MeterRegistry registry;
    private final ConcurrentMap<String, Gradient2Limiter> routes = new ConcurrentHashMap<>();

    public AdaptiveGateway(GateKeeperProperties.Adaptive config, MeterRegistry registry) {
        this.config = config;
        this.shedder = new PriorityShedder(config.shedThresholdP2(), config.shedThresholdP1());
        this.registry = registry != null ? registry : new CompositeMeterRegistry();
    }

    public void onSample(String route, long rttNanos, boolean error) {
        limiter(route).onSample(rttNanos, error);
    }

    public boolean tryAcquire(String route, Priority priority, long maxWaitMs) {
        return limiter(route).tryAcquire(priority, maxWaitMs);
    }

    public void release(String route, Priority priority) {
        limiter(route).release(priority);
    }

    public int currentLimit(String route) {
        return limiter(route).currentLimit();
    }

    public int inflight(String route) {
        return limiter(route).inflight();
    }

    /**
     * Full admission path: sheds droppable then standard traffic as inflight
     * crosses the configured fractions of the current Gradient2 limit, and
     * never sheds P0 (P0 waits up to p0WaitMs and is force-admitted past that).
     * Shed requests must never be fed back through {@link #onSample}.
     */
    public ShedDecision admit(String route, Priority priority) {
        Gradient2Limiter limiter = limiter(route);
        ShedDecision decision = shedder.check(limiter.inflight(), limiter.currentLimit(), priority);
        if (!decision.allowed()) {
            return decision;
        }
        long waitMs = priority == Priority.P0 ? config.p0WaitMs() : 0L;
        if (limiter.tryAcquire(priority, waitMs)) {
            return ShedDecision.admitted();
        }
        if (priority == Priority.P0) {
            limiter.forceAdmit();
            return ShedDecision.admitted();
        }
        return new ShedDecision(false, PriorityShedder.statusWhenShed(priority), shedder.retryAfterSeconds());
    }

    Gradient2Limiter limiter(String route) {
        return routes.computeIfAbsent(route, r -> {
            Gradient2Limiter created = new Gradient2Limiter(
                    config.minLimit(), config.maxLimit(), config.tolerance(), config.smoothing());
            Gauge.builder(LIMIT_GAUGE, created, lim -> lim.currentLimit())
                    .description("Adaptive Gradient2 concurrency limit")
                    .tag("route", r)
                    .register(registry);
            Gauge.builder(INFLIGHT_GAUGE, created, lim -> lim.inflight())
                    .description("Adaptive in-flight requests")
                    .tag("route", r)
                    .register(registry);
            return created;
        });
    }
}
