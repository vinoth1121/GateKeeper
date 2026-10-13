package io.gatekeeper.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.gatekeeper.adaptive.AdaptiveGateway;
import io.gatekeeper.policy.PolicyService;
import io.gatekeeper.resilience.RedisGuard;

/**
 * Stats endpoints backing the demo console.
 *
 * - GET /api/admin/stats (X-Admin-Key required): full internals sourced from
 *   the filter's in-memory DecisionStats tallies, the overhead timer, the
 *   RedisGuard breaker state and the adaptive gateway.
 * - GET /api/public/stats: safe demo subset; this is what the console polls.
 * - GET /api/admin/tenants/top?N=10 (X-Admin-Key required): raw tenant ids
 *   are allowed here because this is admin JSON, not metrics.
 */
@RestController
public class AdminStatsController {

    private static final List<String> ROUTES = List.of("demo", "mock");

    private final AdminKeyGuard adminKeyGuard;
    private final DecisionStats stats;
    private final OverheadMetrics overhead;
    private final AdaptiveGateway adaptive;
    private final ObjectProvider<RedisGuard> redisGuard;
    private final ObjectProvider<PolicyService> policyService;
    private final ChaosFaultInjector chaos;

    public AdminStatsController(AdminKeyGuard adminKeyGuard, DecisionStats stats, OverheadMetrics overhead,
            AdaptiveGateway adaptive, ObjectProvider<RedisGuard> redisGuard,
            ObjectProvider<PolicyService> policyService, ChaosFaultInjector chaos) {
        this.adminKeyGuard = adminKeyGuard;
        this.stats = stats;
        this.overhead = overhead;
        this.adaptive = adaptive;
        this.redisGuard = redisGuard;
        this.policyService = policyService;
        this.chaos = chaos;
    }

    @GetMapping("/api/admin/stats")
    public Map<String, Object> adminStats(HttpServletRequest request, HttpServletResponse response) {
        if (!adminKeyGuard.isValid(request)) {
            response.setStatus(401);
            return Map.of("error", "invalid_admin_key");
        }
        Map<String, Object> body = publicStats();
        body.put("decisionsTotals", stats.decisionTotals());
        body.put("topTenants", stats.topTenants(10));
        body.put("adaptive", adaptiveSnapshot());
        return body;
    }

    @GetMapping("/api/public/stats")
    public Map<String, Object> publicStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestsPerSecond", round(stats.requestsPerSecond()));
        body.put("allowed", stats.totalByDecision("allowed"));
        body.put("denied", stats.totalByDecision("denied"));
        body.put("shedP1", stats.totalByDecision("shed_p1"));
        body.put("shedP2", stats.totalByDecision("shed_p2"));
        body.put("authFailures", stats.totalByDecision("auth"));
        body.put("errors", stats.totalByDecision("error"));
        body.put("circuitBreakerState", circuitBreakerState());
        body.put("policyVersion", policyVersion());
        body.put("overheadP95Millis", round(overhead.p95Millis()));
        body.put("overheadP99Millis", round(overhead.p99Millis()));
        body.put("localHitRatio", round(stats.localHitRatio()));
        body.put("nodeCount", 1);
        body.put("uptimeMs", stats.uptimeMillis());
        body.put("wakingUp", false);
        body.put("simulatedRedisFault", chaos.faultActive());
        return body;
    }

    @GetMapping("/api/admin/tenants/top")
    public List<Map<String, Object>> topTenants(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "N", defaultValue = "10") int limit) {
        if (!adminKeyGuard.isValid(request)) {
            response.setStatus(401);
            return List.of(Map.of("error", "invalid_admin_key"));
        }
        int n = Math.clamp(limit, 1, 100);
        return stats.topTenants(n);
    }

    private Map<String, Object> adaptiveSnapshot() {
        Map<String, Object> routes = new LinkedHashMap<>();
        for (String route : ROUTES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("limit", adaptive.currentLimit(route));
            entry.put("inflight", adaptive.inflight(route));
            routes.put(route, entry);
        }
        return routes;
    }

    private String circuitBreakerState() {
        RedisGuard guard = redisGuard.getIfAvailable();
        if (guard == null) {
            return "unknown";
        }
        try {
            return String.valueOf(guard.state()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private long policyVersion() {
        PolicyService service = policyService.getIfAvailable();
        if (service == null) {
            return -1L;
        }
        try {
            return service.currentVersion();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
