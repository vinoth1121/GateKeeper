package io.gatekeeper.limiter;

public interface RateLimiterService {

    LimiterDecision check(String tenantId, String tier, String keyHash, LimitSpec spec);
}
