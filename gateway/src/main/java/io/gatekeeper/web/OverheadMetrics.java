package io.gatekeeper.web;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

/**
 * Owns the gatekeeper_gateway_overhead_seconds timer so both the filter
 * (recorder) and the stats controllers (percentile readers) share one meter.
 * Percentiles are configured client-side so takeSnapshot exposes p95/p99
 * without a Prometheus scrape round-trip.
 */
@Component
public class OverheadMetrics {

    public static final String OVERHEAD_TIMER = "gatekeeper_gateway_overhead_seconds";

    private final Timer timer;

    public OverheadMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            registry = new CompositeMeterRegistry();
        }
        this.timer = Timer.builder(OVERHEAD_TIMER)
                .description("Gateway-side overhead: filter time before chain.doFilter plus post-chain delta, "
                        + "excluding upstream service time")
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void record(long overheadNanos) {
        timer.record(overheadNanos, TimeUnit.NANOSECONDS);
    }

    public double percentileMillis(double percentile) {
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - percentile) < 1e-6) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return 0.0;
    }

    public double p95Millis() {
        return percentileMillis(0.95);
    }

    public double p99Millis() {
        return percentileMillis(0.99);
    }
}
