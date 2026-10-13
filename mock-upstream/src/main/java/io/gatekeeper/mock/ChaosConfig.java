package io.gatekeeper.mock;

/**
 * Chaos configuration applied to mock upstream responses.
 *
 * @param latencyMs artificial delay in milliseconds, clamped to 0..10000
 * @param errorRate probability of a 500 response, clamped to 0..1
 */
public record ChaosConfig(int latencyMs, double errorRate) {

    public static final ChaosConfig ZERO = new ChaosConfig(0, 0.0);

    public static ChaosConfig clamped(Integer latencyMs, Double errorRate) {
        int latency = latencyMs == null ? 0 : Math.clamp(latencyMs, 0, 10000);
        double rate = errorRate == null ? 0.0 : Math.clamp(errorRate, 0.0, 1.0);
        return new ChaosConfig(latency, rate);
    }
}