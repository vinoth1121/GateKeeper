package io.gatekeeper.limiter;

public record LimiterDecision(
        boolean allowed,
        int remaining,
        long retryAfterMs,
        long resetEpochMs,
        DecisionSource source,
        String reason) {

    public enum DecisionSource { REDIS, LOCAL, DEGRADED }
}
