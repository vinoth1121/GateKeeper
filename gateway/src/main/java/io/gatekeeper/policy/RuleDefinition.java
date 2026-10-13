package io.gatekeeper.policy;

import io.gatekeeper.adaptive.Priority;

/**
 * Defines rate-limiting and priority properties for a policy rule.
 */
public record RuleDefinition(
        String id,
        String algorithm,
        long capacity,
        double refillPerSec,
        long windowMs,
        long max,
        long cost,
        boolean strict,
        Priority priority) {

    public static RuleDefinition defaultRule() {
        return new RuleDefinition("default-rule", "token_bucket", 20L, 10.0, 1000L, 20L, 1L, false, Priority.P1);
    }
}
