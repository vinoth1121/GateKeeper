package io.gatekeeper.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.gatekeeper.adaptive.Priority;
import io.gatekeeper.limiter.LimitSpec;
import io.gatekeeper.policy.RequestContext;
import io.gatekeeper.policy.ResolvedPolicy;
import io.gatekeeper.policy.RuleDefinition;

/**
 * SINGLE INTEGRATION POINT for the policy contract. Every assumption about
 * the io.gatekeeper.policy API surface (constructor/accessor shapes being
 * written by the parallel policy agent) lives here so the final integration
 * pass can reconcile signatures in one file.
 *
 * Current assumptions (per the handed-off contract):
 * - RequestContext record: (String path, String method, Map headers, String ip,
 *   String tenantId, String tier, Set tags, String keyId)
 * - ResolvedPolicy record: (String ruleId, RuleDefinition rule, long version)
 * - RuleDefinition exposes: algorithm() as String ("token_bucket"/"sliding_window"),
 *   capacity(), refillPerSec(), windowMs(), max(), cost(), strict(),
 *   priority() as io.gatekeeper.adaptive.Priority
 */
final class PolicyAdapter {

    static final String FALLBACK_RULE_ID = "builtin-fallback";

    private PolicyAdapter() {
    }

    /** Web-side resolved rule: everything the filter needs, decoupled from policy types. */
    record ResolvedRule(String ruleId, LimitSpec spec, Priority priority, long version, boolean degraded) {
        static ResolvedRule builtin(long version) {
            // Built-in conservative default: token bucket, capacity 10, refill 5/s, fail-open.
            LimitSpec spec = new LimitSpec(FALLBACK_RULE_ID, LimitSpec.Algorithm.TOKEN_BUCKET,
                    10L, 5.0, 1000L, 10L, 1L, false);
            return new ResolvedRule(FALLBACK_RULE_ID, spec, Priority.P1, version, true);
        }
    }

    static RequestContext buildRequestContext(String path, String method, Map<String, String> headers,
            String ip, String tenantId, String tier, java.util.Set<String> tags, String keyId) {
        return new RequestContext(path, method, headers, ip, tenantId, tier, tags, keyId);
    }

    static ResolvedRule toResolvedRule(ResolvedPolicy policy) {
        RuleDefinition rule = policy.rule();
        if (rule == null) {
            return null;
        }
        String ruleId = policy.ruleId() != null ? policy.ruleId() : FALLBACK_RULE_ID;
        return new ResolvedRule(ruleId, limitSpecFrom(rule), priorityFrom(rule), policy.version(), false);
    }

    static LimitSpec limitSpecFrom(RuleDefinition rule) {
        LimitSpec.Algorithm algorithm = parseAlgorithm(rule.algorithm());
        long capacity = positiveOr(rule.capacity(), 10L);
        double refill = rule.refillPerSec() > 0 ? rule.refillPerSec() : 5.0;
        long windowMs = positiveOr(rule.windowMs(), 1000L);
        long max = positiveOr(rule.max(), capacity);
        long cost = positiveOr(rule.cost(), 1L);
        return new LimitSpec(rule.id(), algorithm, capacity, refill, windowMs, max, cost, rule.strict());
    }

    static Priority priorityFrom(RuleDefinition rule) {
        Priority priority = rule.priority();
        return priority != null ? priority : Priority.P1;
    }

    static LimitSpec.Algorithm parseAlgorithm(String algorithm) {
        if (algorithm == null) {
            return LimitSpec.Algorithm.TOKEN_BUCKET;
        }
        return switch (algorithm.trim().toLowerCase(Locale.ROOT)) {
            case "sliding_window", "sliding-window", "slidingwindow" -> LimitSpec.Algorithm.SLIDING_WINDOW;
            default -> LimitSpec.Algorithm.TOKEN_BUCKET;
        };
    }

    static Map<String, String> singleValuedHeaders(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (var names = request.getHeaderNames(); names != null && names.hasMoreElements();) {
            String name = names.nextElement();
            if (name != null) {
                headers.putIfAbsent(name, request.getHeader(name));
            }
        }
        return headers;
    }

    private static long positiveOr(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
