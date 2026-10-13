package io.gatekeeper.adaptive;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error and shed outcomes must never pollute the RTT statistics that drive
 * the Gradient2 limit: error samples degrade (clear) the short window without
 * entering it, and the long window is untouched by them. Shed requests are
 * never sampled at all by the wiring, which is enforced by convention on
 * {@link AdaptiveGateway#admit} callers.
 */
class SampleIgnoreTest {

    private static long ms(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    private final Gradient2Limiter limiter = new Gradient2Limiter(4, 1024, 2.0, 0.2);

    @Test
    void errorSamplesNeverEnterRttWindowsOrMoveTheLimit() {
        for (int i = 0; i < 20; i++) {
            limiter.onSample(ms(50), false);
        }
        double shortBefore = limiter.shortRttNanos();
        double longBefore = limiter.longRttNanos();
        int limitBefore = limiter.currentLimit();
        assertThat(shortBefore).isEqualTo(ms(50));
        assertThat(longBefore).isEqualTo(ms(50));

        for (int i = 0; i < 5; i++) {
            limiter.onSample(ms(999), true);
        }
        System.out.println("after errors: shortRttMs=" + (limiter.shortRttNanos() / 1_000_000.0)
                + " longRttMs=" + (limiter.longRttNanos() / 1_000_000.0)
                + " limit=" + limiter.currentLimit() + " (limitBefore=" + limitBefore + ")");

        assertThat(limiter.currentLimit()).isEqualTo(limitBefore);
        assertThat(limiter.shortRttNanos()).isLessThan(ms(999));
        assertThat(limiter.longRttNanos()).isEqualTo(ms(50));

        for (int i = 0; i < 6; i++) {
            limiter.onSample(ms(50), false);
        }
        System.out.println("after refill: shortRttMs=" + (limiter.shortRttNanos() / 1_000_000.0)
                + " limit=" + limiter.currentLimit());
        assertThat(limiter.shortRttNanos()).isEqualTo(ms(50));
        assertThat(limiter.currentLimit()).isGreaterThan(limitBefore);
    }

    @Test
    void identicalLatencySampledAsSuccessDoesShrinkTheLimit() {
        Gradient2Limiter control = new Gradient2Limiter(4, 1024, 2.0, 0.2);
        for (int i = 0; i < 20; i++) {
            control.onSample(ms(50), false);
        }
        int before = control.currentLimit();
        for (int i = 0; i < 5; i++) {
            control.onSample(ms(999), false);
        }
        int after = control.currentLimit();
        System.out.println("control(success samples): before=" + before + " after=" + after);
        assertThat(after).isLessThan(before);
    }
}
