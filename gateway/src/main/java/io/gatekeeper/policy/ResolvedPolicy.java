package io.gatekeeper.policy;

/**
 * The outcome of evaluating policies against a RequestContext.
 */
public record ResolvedPolicy(
        String ruleId,
        RuleDefinition rule,
        long version) {
}
