package io.gatekeeper.resilience;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Resilience4j CircuitBreaker + TimeLimiter around ALL Redis calls.
 *
 * Every guarded call runs on a virtual-thread executor under a
 * Future.get(callTimeoutMs) deadline inside breaker.decorateSupplier; on
 * timeout or an open breaker the fallback runs. Before the real call the
 * FaultInjector hook is consulted (simulated latency / outage; no-op default).
 */
public final class RedisGuard {

    public static final String STATE_METRIC = "gatekeeper.circuit.breaker.state";
    public static final String NODE_COUNT_METRIC = "gatekeeper.node.count";

    private final CircuitBreaker breaker;
    private final long callTimeoutMs;
    private final FaultInjector injector;
    private final GateKeeperMeters meters;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger liveNodes = new AtomicInteger(1);

    public RedisGuard(GateKeeperProperties properties, GateKeeperMeters meters,
            FaultInjector injector, MeterRegistry registry) {
        GateKeeperProperties.Redis redis = properties.redis();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(redis.breakerSlidingWindowSize())
                .minimumNumberOfCalls(Math.max(2, redis.breakerSlidingWindowSize() / 10))
                .failureRateThreshold(redis.breakerFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(redis.breakerWaitMs()))
                .permittedNumberOfCallsInHalfOpenState(redis.breakerHalfOpenCalls())
                .build();
        this.breaker = CircuitBreaker.of("gatekeeper-redis", config);
        this.callTimeoutMs = redis.callTimeoutMs();
        this.injector = injector;
        this.meters = meters;
        MeterRegistry reg = registry != null ? registry : new CompositeMeterRegistry();
        Gauge.builder(STATE_METRIC, this, g -> g.state().ordinal())
                .description("Circuit breaker state: 0 CLOSED, 1 HALF_OPEN, 2 OPEN")
                .register(reg);
        Gauge.builder(NODE_COUNT_METRIC, this, RedisGuard::liveNodeCount)
                .description("Live gateway nodes seen via heartbeat")
                .register(reg);
    }

    /**
     * Runs a Redis operation under the circuit breaker and call-timeout
     * deadline; any timeout, open breaker or error falls back to fallback.get().
     */
    public <T> T call(String op, Supplier<T> redisCall, Supplier<T> fallback) {
        try {
            return CircuitBreaker.decorateSupplier(breaker, () -> execute(op, redisCall)).get();
        } catch (RuntimeException e) {
            return fallback.get();
        }
    }

    private <T> T execute(String op, Supplier<T> redisCall) {
        long start = System.nanoTime();
        try {
            if (injector != null) {
                if (injector.injectFailure(op)) {
                    throw new RedisCallFailedException("injected fault: " + op);
                }
                Duration latency = injector.injectedLatency(op);
                if (latency != null) {
                    Thread.sleep(Math.max(1L, latency.toMillis()));
                }
            }
            Future<T> future = executor.submit(redisCall::get);
            try {
                return future.get(callTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RedisCallFailedException("redis call timeout: " + op, e);
            } catch (ExecutionException e) {
                throw asRuntime(e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisCallFailedException("redis call interrupted: " + op, e);
        } finally {
            meters.redisCallTotal(op).increment();
            meters.redisTimer(op).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    public int liveNodeCount() {
        return liveNodes.get();
    }

    public void updateLiveNodeCount(int count) {
        liveNodes.set(Math.max(1, count));
    }

    public void close() {
        executor.shutdownNow();
    }

    private static RuntimeException asRuntime(Throwable t) {
        return t instanceof RuntimeException r ? r : new RedisCallFailedException(String.valueOf(t), t);
    }

    /** Timeout / outage / injected failure of a guarded Redis call. */
    public static final class RedisCallFailedException extends RuntimeException {
        public RedisCallFailedException(String message) {
            super(message);
        }

        public RedisCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
