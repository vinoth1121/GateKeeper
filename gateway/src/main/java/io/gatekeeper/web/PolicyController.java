package io.gatekeeper.web;

import java.util.Map;

import io.gatekeeper.adaptive.Priority;
import io.gatekeeper.policy.CelPolicyService;
import io.gatekeeper.policy.RuleDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PolicyController {

    private final CelPolicyService policyService;
    private final String adminKey;

    public PolicyController(
            CelPolicyService policyService,
            @Value("${gatekeeper.admin-key:${GATEKEEPER_ADMIN_KEY:dev-admin-key-change-me}}") String adminKey) {
        this.policyService = policyService;
        this.adminKey = adminKey;
    }

    @GetMapping("/policies/current")
    public ResponseEntity<Map<String, Object>> currentPolicy() {
        return ResponseEntity.ok(Map.of(
                "version", policyService.currentVersion(),
                "policyVersion", policyService.currentVersion(),
                "rules", policyService.activeRuleSummaries()
        ));
    }

    @PostMapping("/policies/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(@RequestBody Map<String, Object> body) {
        String expression = extractExpression(body);
        if (expression == null || expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false,
                    "error", "missing expression or when clause",
                    "position", 0
            ));
        }

        Map<String, Object> sampleVars = extractSampleVars(body);
        CelPolicyService.ValidationResult result = policyService.dryRun(expression, sampleVars);
        if (!result.valid()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false,
                    "error", result.error() != null ? result.error() : "Compilation error",
                    "position", result.position() != null ? result.position() : 0
            ));
        }

        return ResponseEntity.ok(Map.of(
                "valid", true,
                "matched", result.matched() != null ? result.matched() : false
        ));
    }

    @PostMapping("/sandbox/policies")
    public ResponseEntity<Map<String, Object>> publishSandbox(@RequestBody Map<String, Object> body) {
        String expression = extractExpression(body);
        if (expression == null || expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing expression or when clause",
                    "position", 0
            ));
        }

        RuleDefinition rule = extractRuleDefinition(body);
        CelPolicyService.PublishResult result = policyService.publishSandbox(expression, rule);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", result.error() != null ? result.error() : "Sandbox publish rejected",
                    "position", result.position() != null ? result.position() : 0
            ));
        }

        return ResponseEntity.ok(Map.of(
                "version", result.version(),
                "policyVersion", result.version()
        ));
    }

    @PostMapping("/admin/policies")
    public ResponseEntity<Map<String, Object>> publishAdmin(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if (adminKey == null || adminKey.isBlank() || key == null || !adminKey.equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }

        String expression = extractExpression(body);
        if (expression == null || expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing expression or when clause",
                    "position", 0
            ));
        }

        String ruleId = (String) body.get("ruleId");
        RuleDefinition rule = extractRuleDefinition(body);
        CelPolicyService.PublishResult result = policyService.publishAdmin(ruleId, expression, rule);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", result.error() != null ? result.error() : "Admin publish rejected",
                    "position", result.position() != null ? result.position() : 0
            ));
        }

        return ResponseEntity.ok(Map.of(
                "version", result.version(),
                "policyVersion", result.version()
        ));
    }

    private String extractExpression(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        if (body.get("expression") instanceof String s) {
            return s;
        }
        if (body.get("when") instanceof String s) {
            return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private RuleDefinition extractRuleDefinition(Map<String, Object> body) {
        if (body == null || !(body.get("limit") instanceof Map<?, ?> rawLimit)) {
            return RuleDefinition.defaultRule();
        }
        Map<String, Object> limit = (Map<String, Object>) rawLimit;
        String algorithm = (String) limit.getOrDefault("algorithm", "token_bucket");
        long capacity = parseLong(limit.get("capacity"), 20L);
        double refill = parseDouble(limit.get("refillPerSec"), 10.0);
        long windowMs = parseLong(limit.get("windowMs"), 1000L);
        long max = parseLong(limit.get("max"), capacity);
        long cost = parseLong(limit.get("cost"), 1L);
        boolean strict = Boolean.TRUE.equals(limit.get("strict"));
        return new RuleDefinition("rule-" + System.currentTimeMillis(), algorithm, capacity, refill, windowMs, max, cost, strict, Priority.P1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSampleVars(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        if (body.containsKey("request") || body.containsKey("tenant")) {
            Map<String, Object> request = (body.get("request") instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
            Map<String, Object> tenant = (body.get("tenant") instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
            String keyId = (body.get("keyId") instanceof String s) ? s : "demo";
            return Map.of("request", request, "tenant", tenant, "keyId", keyId);
        }
        return null;
    }

    private long parseLong(Object val, long fallback) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double parseDouble(Object val, double fallback) {
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
