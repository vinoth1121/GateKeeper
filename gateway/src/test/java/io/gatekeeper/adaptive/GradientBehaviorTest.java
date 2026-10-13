package io.gatekeeper.adaptive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic Gradient2 behavior over simulated RTT sequences: baseline
 * growth toward maxLimit, shrink under a latency jump, recovery on
 * normalization. All prints below are the measured evidence values.
 */
class GradientBehaviorTest {

    private static final int MIN_LIMIT = 4;
    private static final int MAX_LIMIT = 1024;
    private static final long BASELINE_MS = 50;
    private static final long CONGESTED_MS = 500;

    private static long ms(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    private final Gradient2Limiter limiter = new Gradient2Limiter(MIN_LIMIT, MAX_LIMIT, 2.0, 0.2);

    private int warm(int samples) {
        for (int i = 0; i < samples; i++) {
            limiter.onSample(ms(BASELINE_MS), false);
            assertWithinBounds();
        }
        return limiter.currentLimit();
    }

    private void assertWithinBounds() {
        int current = limiter.currentLimit();
        assertThat(current).isBetween(MIN_LIMIT, MAX_LIMIT);
        assertThat(Double.isFinite(limiter.shortRttNanos())).isTrue();
        assertThat(Double.isFinite(limiter.longRttNanos())).isTrue();
    }

    @Test
    void baselineRttGrowsLimitTowardMaxButNeverBeyondIt() {
        int start = limiter.currentLimit();
        List<Integer> trace = new ArrayList<>();
        for (int i = 1; i <= 350; i++) {
            limiter.onSample(ms(BASELINE_MS), false);
            assertWithinBounds();
            if (i % 50 == 0) {
                trace.add(limiter.currentLimit());
            }
        }
        int stable = limiter.currentLimit();
        System.out.println("warmup: start=" + start
                + " checkpoints(every50)=" + trace
                + " stable=" + stable);
        assertThat(start).isEqualTo(MIN_LIMIT);
        assertThat(trace).allMatch(v -> v <= MAX_LIMIT);
        for (int i = 1; i < trace.size(); i++) {
            assertThat(trace.get(i)).isGreaterThan(trace.get(i - 1));
        }
        assertThat(stable).isEqualTo(MAX_LIMIT);
    }

    @Test
    void latencyJumpShrinksLimitWithinSamples() {
        int stable = warm(350);
        List<Integer> trace = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            limiter.onSample(ms(CONGESTED_MS), false);
            assertWithinBounds();
            trace.add(limiter.currentLimit());
        }
        int min = trace.stream().mapToInt(Integer::intValue).min().orElseThrow();
        System.out.println("congested(500ms): stable=" + stable
                + " trace=" + trace
                + " min=" + min
                + " shrink=" + (stable - min));
        assertThat(trace.get(0)).isLessThan(stable);
        assertThat(min).isLessThan(stable);
        assertThat(min).isLessThan(700);
    }

    @Test
    void normalizedRttRecoversLimitUpward() {
        warm(350);
        List<Integer> congested = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            limiter.onSample(ms(CONGESTED_MS), false);
            assertWithinBounds();
            congested.add(limiter.currentLimit());
        }
        int congestedEnd = limiter.currentLimit();
        int congestedMin = congested.stream().mapToInt(Integer::intValue).min().orElseThrow();

        List<Integer> recovery = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            limiter.onSample(ms(BASELINE_MS), false);
            assertWithinBounds();
            recovery.add(limiter.currentLimit());
        }
        int recovered = limiter.currentLimit();
        System.out.println("recovery(50ms): congestedMin=" + congestedMin
                + " congestedEnd=" + congestedEnd
                + " firstRecovery=" + recovery.get(0)
                + " recovered=" + recovered
                + " gain=" + (recovered - congestedEnd));
        for (int i = 1; i < recovery.size(); i++) {
            assertThat(recovery.get(i)).isGreaterThanOrEqualTo(recovery.get(i - 1));
        }
        assertThat(recovery.get(0)).isGreaterThan(congestedEnd);
        assertThat(recovered).isGreaterThan(congestedEnd + 100);
        assertThat(recovered).isLessThanOrEqualTo(MAX_LIMIT);
    }
}
