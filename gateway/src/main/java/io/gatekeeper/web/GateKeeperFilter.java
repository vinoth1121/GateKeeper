package io.gatekeeper.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.gatekeeper.adaptive.AdaptiveGateway;
import io.gatekeeper.adaptive.Priority;
import io.gatekeeper.adaptive.ShedDecision;
import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LimiterDecision;
import io.gatekeeper.limiter.RateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.gatekeeper.policy.PolicyService;
import io.gatekeeper.policy.ResolvedPolicy;
import io.gatekeeper.tenant.ApiKeyAuthenticationService;
import io.gatekeeper.tenant.ClientInfo;
import io.gatekeeper.tenant.ClientIpResolver;
import io.gatekeeper.tenant.Tenant;
import io.gatekeeper.tenant.Tier;

/**
 * Edge pipeline for proxied paths (/demo/**, /mock/** only).
 *
 * Pipeline: request-shape limits (header count/size -> 400, content-length ->
 * 413, both JSON) -> API-key auth (401 {"error":"invalid_api_key"}) -> client
 * IP resolve -> policy resolve (fail-open to a built-in conservative rule,
 * decision marked degraded) -> priority (rule + trusted ENTERPRISE-only
 * X-GateKeeper-Priority override) -> adaptive admission (P2 shed 429, P1 shed
 * 503, P0 waits) -> distributed rate limit -> proxy.
 *
 * Overhead definition (recorded in gatekeeper_gateway_overhead_seconds,
 * honestly gateway-side only):
 *   tStart          = filter entry
 *   tUpstreamStart  = just before chain.doFilter, i.e. after every decision
 *   tUpstreamEnd    = right after chain.doFilter returns
 *   tEnd            = after post-chain bookkeeping (release/onSample/record)
 *   overheadNanos   = (tUpstreamStart - tStart)      // decision time
 *                   + (tEnd - tUpstreamEnd)          // post-chain commit delta
 * i.e. total time the request spent inside the gateway filter minus the time
 * spent in the downstream chain (upstream service + SCG-MVC proxying). It is
 * NOT upstream service time and makes no claim to be.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GateKeeperFilter extends OncePerRequestFilter {

    static final String DECISION_HEADER = "X-GateKeeper-Decision";
    static final String RULE_HEADER = "X-GateKeeper-Rule";
    static final String POLICY_VERSION_HEADER = "X-GateKeeper-Policy-Version";
    static final String PRIORITY_OVERRIDE_HEADER = "X-GateKeeper-Priority";
    static final int KEY_HASH_PREFIX_LENGTH = 16;

    private static final Logger log = LoggerFactory.getLogger(GateKeeperFilter.class);

    private final GateKeeperProperties properties;
    private final ApiKeyAuthenticationService authentication;
    private final ClientIpResolver ipResolver;
    private final ObjectProvider<PolicyService> policyServiceProvider;
    private final AdaptiveGateway adaptive;
    private final ObjectProvider<RateLimiterService> rateLimiterProvider;
    private final GateKeeperMeters meters;
    private final DecisionStats stats;
    private final OverheadMetrics overhead;

    public GateKeeperFilter(GateKeeperProperties properties,
            ApiKeyAuthenticationService authentication,
            ClientIpResolver ipResolver,
            ObjectProvider<PolicyService> policyServiceProvider,
            AdaptiveGateway adaptive,
            ObjectProvider<RateLimiterService> rateLimiterProvider,
            GateKeeperMeters meters,
            DecisionStats stats,
            OverheadMetrics overhead) {
        this.properties = properties;
        this.authentication = authentication;
        this.ipResolver = ipResolver;
        this.policyServiceProvider = policyServiceProvider;
        this.adaptive = adaptive;
        this.rateLimiterProvider = rateLimiterProvider;
        this.meters = meters;
        this.stats = stats;
        this.overhead = overhead;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/demo") || path.equals("/mock")
                || path.startsWith("/demo/") || path.startsWith("/mock/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long tStart = System.nanoTime();
        String route = routeOf(request);

        // --- 1. request-shape limits (400/413, JSON only) ---
        if (headersExceedLimits(request)) {
            countAndRespond("unknown", "none", "error", "headers_exceeded", response, 400,
                    Jsons.object("error", "headers_exceeded"));
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.limits().requestBodyMaxBytes()) {
            countAndRespond("unknown", "none", "error", "payload_too_large", response, 413,
                    Jsons.object("error", "payload_too_large"));
            return;
        }

        // --- 2. authentication ---
        Tenant tenant = null;
        try {
            tenant = authentication.resolve(request).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Authentication resolution failed", e);
        }
        if (tenant == null) {
            countAndRespond("unknown", "none", "auth", "invalid_api_key", response, 401,
                    Jsons.object("error", "invalid_api_key"));
            return;
        }
        String tier = tenant.tier().name();

        // --- 3. client info ---
        ClientInfo client;
        try {
            client = ipResolver.resolve(request);
        } catch (RuntimeException e) {
            client = new ClientInfo(request.getRemoteAddr(), false);
        }

        // --- 4. policy resolution (fail-open, never 5xx from this filter) ---
        PolicyService policyService = policyServiceProvider.getIfAvailable();
        PolicyAdapter.ResolvedRule rule = resolveRule(policyService, request, tenant, client);

        // --- 5. priority: rule default, trusted override for ENTERPRISE tenants ---
        Priority priority = trustedPriorityOverride(request, tenant).orElse(rule.priority());

        // --- 6. adaptive admission ---
        ShedDecision shed = adaptive.admit(route, priority);
        if (!shed.allowed()) {
            try {
                response.setStatus(shed.statusWhenShed());
                response.setHeader(DECISION_HEADER, "denied");
                response.setHeader(RULE_HEADER, rule.ruleId());
                response.setHeader(POLICY_VERSION_HEADER, Long.toString(rule.version()));
                response.setHeader("RateLimit-Limit", Long.toString(rule.spec().capacity()));
                response.setHeader("Retry-After", Long.toString(Math.max(1L, shed.retryAfterSeconds())));
                Jsons.write(response, shed.statusWhenShed(), Jsons.object("error", "overloaded"));
            } finally {
                adaptive.release(route, priority);
                // Shed requests never reach upstream and must not feed onSample.
                countDecision(tier, rule.ruleId(),
                        priority == Priority.P2 ? "shed_p2" : "shed_p1", "concurrency");
            }
            return;
        }

        // --- 7. distributed rate limit (prefer the leasing-wrapped limiter) ---
        LimiterDecision decision;
        try {
            decision = limiter().check(tenant.id(), tier, keyHashOf(tenant), rule.spec());
        } catch (RuntimeException e) {
            log.debug("Rate limiter call failed; failing open in degraded mode", e);
            decision = new LimiterDecision(true, 0, 0L, 0L,
                    LimiterDecision.DecisionSource.DEGRADED, "limiter_error");
        }
        stats.countSource(decision.source());
        stats.countTenant(tenant.id());

        writeDecisionHeaders(response, rule, decision);

        if (!decision.allowed()) {
            try {
                long retryAfterSeconds = Math.max(1L, (decision.retryAfterMs() + 999) / 1000);
                response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
                Jsons.write(response, 429, Jsons.object("error", "rate_limit_exceeded"));
            } finally {
                adaptive.release(route, priority);
                // Denied requests never reach upstream: no adaptive sample.
                countDecision(tier, rule.ruleId(), "denied",
                        decision.reason() == null ? "limit_exceeded" : decision.reason());
            }
            return;
        }

        countDecision(tier, rule.ruleId(), "allowed",
                decision.reason() == null ? "ok" : decision.reason());

        // --- 8. proxy with honest overhead accounting ---
        long tUpstreamStart = System.nanoTime();
        CommitTrackingResponseWrapper wrapper = new CommitTrackingResponseWrapper(response);
        boolean upstreamError = false;
        try {
            chain.doFilter(request, wrapper);
            upstreamError = wrapper.getStatus() >= 500;
        } catch (IOException | ServletException | RuntimeException e) {
            upstreamError = true;
            log.debug("Proxy dispatch failed", e);
            if (!response.isCommitted()) {
                Jsons.write(response, 502, Jsons.object("error", "bad_gateway"));
                countDecision(tier, rule.ruleId(), "error", "bad_gateway");
            }
            return;
        } finally {
            long tUpstreamEnd = System.nanoTime();
            adaptive.release(route, priority);
            adaptive.onSample(route, tUpstreamEnd - tUpstreamStart, upstreamError);
            long tEnd = System.nanoTime();
            // Gateway-side overhead only: decision time + post-chain delta.
            overhead.record((tUpstreamStart - tStart) + (tEnd - tUpstreamEnd));
        }
    }

    private void countDecision(String tier, String ruleId, String decision, String reason) {
        stats.count(tier, ruleId, decision, reason);
        meters.decisionsTotal(tier, ruleId, decision, reason).increment();
    }

    private void countAndRespond(String tier, String ruleId, String decision, String reason,
            HttpServletResponse response, int status, String json) throws IOException {
        countDecision(tier, ruleId, decision, reason);
        Jsons.write(response, status, json);
    }

    private void writeDecisionHeaders(HttpServletResponse response, PolicyAdapter.ResolvedRule rule,
            LimiterDecision decision) {
        response.setHeader("RateLimit-Limit", Long.toString(rule.spec().capacity()));
        response.setHeader("RateLimit-Remaining", Integer.toString(Math.max(0, decision.remaining())));
        response.setHeader("RateLimit-Reset", Long.toString(Math.max(0L, decision.resetEpochMs() / 1000L)));
        response.setHeader(RULE_HEADER, rule.ruleId());
        response.setHeader(POLICY_VERSION_HEADER, Long.toString(rule.version()));
        response.setHeader(DECISION_HEADER, decisionLabel(decision));
    }

    static String decisionLabel(LimiterDecision decision) {
        if (decision.source() == LimiterDecision.DecisionSource.LOCAL) {
            return "local";
        }
        if (decision.source() == LimiterDecision.DecisionSource.DEGRADED) {
            return "degraded";
        }
        return decision.allowed() ? "allowed" : "denied";
    }

    /** sha256 prefix of the tenant's API key hash: distinct limiter identity per key. */
    static String keyHashOf(Tenant tenant) {
        String hash = tenant.keyHash();
        if (hash == null || hash.isBlank()) {
            hash = tenant.keyId();
        }
        return hash.length() <= KEY_HASH_PREFIX_LENGTH ? hash : hash.substring(0, KEY_HASH_PREFIX_LENGTH);
    }

    private Optional<Priority> trustedPriorityOverride(HttpServletRequest request, Tenant tenant) {
        if (tenant.tier() != Tier.ENTERPRISE) {
            return Optional.empty();
        }
        String header = request.getHeader(PRIORITY_OVERRIDE_HEADER);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Priority.valueOf(header.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the matched rule. Falls back (in order) to the policy service's
     * default rule when exposed, else the built-in conservative rule; the
     * filter then continues in degraded mode instead of failing the request.
     */
    private PolicyAdapter.ResolvedRule resolveRule(PolicyService policyService, HttpServletRequest request,
            Tenant tenant, ClientInfo client) {
        long version = -1L;
        if (policyService != null) {
            try {
                version = policyService.currentVersion();
            } catch (RuntimeException e) {
                log.debug("Policy version lookup failed", e);
            }
            try {
                ResolvedPolicy policy = policyService.resolve(PolicyAdapter.buildRequestContext(
                        request.getRequestURI(),
                        request.getMethod(),
                        PolicyAdapter.singleValuedHeaders(request),
                        client.ip(),
                        tenant.id(),
                        tenant.tier().name(),
                        tenant.tags(),
                        tenant.keyId()));
                PolicyAdapter.ResolvedRule resolved = PolicyAdapter.toResolvedRule(policy);
                if (resolved != null) {
                    return resolved;
                }
            } catch (RuntimeException e) {
                log.debug("Policy resolution failed; applying default rule in degraded mode", e);
            }
            PolicyAdapter.ResolvedRule defaultRule = defaultRuleIfAvailable(policyService, version);
            if (defaultRule != null) {
                return defaultRule;
            }
        }
        return PolicyAdapter.ResolvedRule.builtin(version);
    }

    /** Optional hook: PolicyService.defaultRule() if the policy agent exposed one. */
    private PolicyAdapter.ResolvedRule defaultRuleIfAvailable(PolicyService policyService, long version) {
        try {
            Object rule = policyService.getClass().getMethod("defaultRule").invoke(policyService);
            if (rule != null) {
                return new PolicyAdapter.ResolvedRule(
                        PolicyAdapter.FALLBACK_RULE_ID,
                        PolicyAdapter.limitSpecFrom((io.gatekeeper.policy.RuleDefinition) rule),
                        PolicyAdapter.priorityFrom((io.gatekeeper.policy.RuleDefinition) rule),
                        version, true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("No usable defaultRule() on PolicyService", e);
        }
        return null;
    }

    /** Prefers the lease-wrapped limiter bean when present, else the plain Redis one. */
    private RateLimiterService limiter() {
        Optional<RateLimiterService> leasing = rateLimiterProvider.orderedStream()
                .filter(l -> l.getClass().getName().contains("Leasing"))
                .findFirst();
        return leasing.orElseGet(rateLimiterProvider::getIfAvailable);
    }

    private boolean headersExceedLimits(HttpServletRequest request) {
        GateKeeperProperties.Limits limits = properties.limits();
        int count = 0;
        long totalBytes = 0;
        for (var names = request.getHeaderNames(); names != null && names.hasMoreElements();) {
            String name = names.nextElement();
            count++;
            totalBytes += name == null ? 0 : name.length();
            for (var values = request.getHeaders(name); values.hasMoreElements();) {
                String value = values.nextElement();
                totalBytes += value == null ? 0 : value.length();
            }
        }
        return count > limits.headerCountMax() || totalBytes > limits.headerSizeMaxBytes();
    }

    static String routeOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        int start = path.startsWith("/") ? 1 : 0;
        int slash = path.indexOf('/', start);
        return slash > 0 ? path.substring(start, slash) : path.substring(start);
    }
}
