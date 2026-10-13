package io.gatekeeper.adaptive;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adaptive concurrency limiter implementing the Gradient2 algorithm from
 * Netflix's concurrency-limits library. The gradient math below is Netflix's,
 * credited here and re-implemented from scratch for GateKeeper; none of this
 * code is copied from that library.
 *
 * Per admitted, non-error request the caller feeds an RTT sample:
 *
 *   gradient = clamp(tolerance * longRtt / shortRtt, 0.5, 1.0)
 *   queue    = sqrt(limit)
 *   newLimit = limit * gradient + queue
 *   limit    = limit * (1 - smoothing) + newLimit * smoothing   in [minLimit, maxLimit]
 *
 * shortRtt is the mean of the last few samples (small window), longRtt is an
 * exponential moving average refreshed once per completed short window.
 * Samples from errored requests never enter either window; an error instead
 * degrades (clears) the short window so stale latency cannot hide a stall.
 *
 * All mutable state lives in atomics: no synchronized blocks anywhere, so
 * admission checks stay safe under virtual-thread concurrency.
 */
public final class Gradient2Limiter {

    /** Mean of the last 4-6 samples per the Gradient2 design; 5 sits mid-range. */
    static final int SHORT_WINDOW = 5;
    private static final long POLL_MS = 5;

    private final int minLimit;
    private final int maxLimit;
    private final double tolerance;
    private final double smoothing;

    private final AtomicReference<Double> limit;
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLongArray shortWindow = new AtomicLongArray(SHORT_WINDOW);
    private final AtomicInteger shortCursor = new AtomicInteger();
    private final AtomicInteger shortFilled = new AtomicInteger();
    private final AtomicReference<Double> longRtt = new AtomicReference<>(0.0);

    public Gradient2Limiter(int minLimit, int maxLimit, double tolerance, double smoothing) {
        if (minLimit < 1 || maxLimit < minLimit) {
            throw new IllegalArgumentException("invalid limits [" + minLimit + ", " + maxLimit + "]");
        }
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.tolerance = tolerance;
        this.smoothing = smoothing;
        this.limit = new AtomicReference<>((double) minLimit);
    }

    /**
     * Records one request outcome. Callers must only sample admitted requests;
     * shed requests must never be sampled at all. An error=true sample is
     * ignored statistically and degrades the short window.
     */
    public void onSample(long rttNanos, boolean error) {
        if (error) {
            degradeShortWindow();
            return;
        }
        if (rttNanos <= 0) {
            return;
        }
        int idx = Math.floorMod(shortCursor.getAndIncrement(), SHORT_WINDOW);
        shortWindow.set(idx, rttNanos);
        shortFilled.updateAndGet(filled -> Math.min(filled + 1, SHORT_WINDOW));

        double shortMean = shortMean();
        if (!(shortMean > 0)) {
            return;
        }
        double longNow = longRtt.updateAndGet(curr -> curr <= 0.0 ? (double) rttNanos : curr);
        if (!(longNow > 0)) {
            return;
        }
        updateLimit(longNow, shortMean);
        if (idx == SHORT_WINDOW - 1) {
            longRtt.updateAndGet(curr -> curr <= 0.0 ? shortMean : curr * (1.0 - smoothing) + shortMean * smoothing);
        }
    }

    /**
     * Attempts to take one of the limit's concurrency slots. When the limit is
     * saturated and {@code maxWaitMs} is positive the caller waits in short
     * polling steps until a slot frees or the wait budget expires. The
     * priority is carried for API symmetry; wait policy is decided by the
     * caller ({@link AdaptiveGateway} only grants a wait budget to P0).
     */
    public boolean tryAcquire(Priority priority, long maxWaitMs) {
        long deadlineNanos =
                maxWaitMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs) : 0L;
        while (true) {
            int current = inflight.get();
            if (current < currentLimit()) {
                if (inflight.compareAndSet(current, current + 1)) {
                    return true;
                }
                continue;
            }
            if (maxWaitMs <= 0 || System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Admits a request over the limit. Reserved for critical traffic that must never shed. */
    public void forceAdmit() {
        inflight.incrementAndGet();
    }

    public void release(Priority priority) {
        inflight.updateAndGet(current -> Math.max(0, current - 1));
    }

    public int currentLimit() {
        double value = limit.get();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return minLimit;
        }
        int rounded = (int) Math.round(value);
        return Math.min(maxLimit, Math.max(minLimit, rounded));
    }

    public int inflight() {
        return inflight.get();
    }

    /** Mean of the filled short-window slots; 0 when the window is degraded or empty. */
    public double shortRttNanos() {
        return shortMean();
    }

    public double longRttNanos() {
        return longRtt.get();
    }

    private void updateLimit(double longRtt, double shortRtt) {
        double current = limit.get();
        if (!(current > 0) || !(shortRtt > 0)) {
            return;
        }
        double gradient = Math.min(Math.max(tolerance * longRtt / shortRtt, 0.5), 1.0);
        int queueSize = (int) Math.sqrt(current);
        double newLimit = current * gradient + queueSize;
        double next = current * (1.0 - smoothing) + newLimit * smoothing;
        double clamped = Math.min(maxLimit, Math.max(minLimit, next));
        if (Double.isNaN(clamped) || Double.isInfinite(clamped)) {
            return;
        }
        limit.set(clamped);
    }

    private void degradeShortWindow() {
        for (int i = 0; i < SHORT_WINDOW; i++) {
            shortWindow.set(i, 0L);
        }
        shortFilled.set(0);
    }

    private double shortMean() {
        int filled = shortFilled.get();
        if (filled == 0) {
            return 0.0;
        }
        int end = shortCursor.get();
        double sum = 0.0;
        for (int i = 1; i <= filled; i++) {
            int idx = Math.floorMod(end - i, SHORT_WINDOW);
            sum += shortWindow.get(idx);
        }
        return sum / filled;
    }
}
