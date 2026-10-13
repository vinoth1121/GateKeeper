package io.gatekeeper.policy;

import java.util.Map;
import java.util.Set;

import io.gatekeeper.adaptive.Priority;
import io.gatekeeper.config.GateKeeperProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CelPolicyTest {

    private CelPolicyService service;

    @BeforeEach
    void setUp() {
        GateKeeperProperties properties = new GateKeeperProperties(
                null,
                null,
                null,
                new GateKeeperProperties.Policy(2000L, 4096, 64, 64, "demo-sandbox", 600000L),
                null,
                null,
                null
        );
        service = new CelPolicyService(properties);
    }

    @Test
    void validExpressionDryRunMatches() {
        CelPolicyService.ValidationResult result = service.dryRun(
                "request.path == '/demo/hello'",
                Map.of(
                        "request", Map.of("path", "/demo/hello", "method", "GET", "headers", Map.of(), "ip", "1.2.3.4"),
                        "tenant", Map.of("id", "demo-sandbox", "tier", "FREE", "tags", java.util.List.of()),
                        "keyId", "k1"
                )
        );
        assertThat(result.valid()).isTrue();
        assertThat(result.matched()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void validExpressionDryRunDoesNotMatchDifferentPath() {
        CelPolicyService.ValidationResult result = service.dryRun(
                "request.path == '/demo/hello'",
                Map.of(
                        "request", Map.of("path", "/other/path", "method", "GET", "headers", Map.of(), "ip", "1.2.3.4"),
                        "tenant", Map.of("id", "demo-sandbox", "tier", "FREE", "tags", java.util.List.of()),
                        "keyId", "k1"
                )
        );
        assertThat(result.valid()).isTrue();
        assertThat(result.matched()).isFalse();
    }

    @Test
    void compileErrorCarriesPositionAndMessage() {
        CelPolicyService.ValidationResult result = service.dryRun("1 ++", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isNotNull();
        assertThat(result.position()).isNotNull();
        System.out.println("CEL Syntax Error Verified: " + result.error() + " at column " + result.position());
    }

    @Test
    void sizeLimitEnforced() {
        String giantExpression = "true && " + "a".repeat(5000);
        CelPolicyService.ValidationResult result = service.dryRun(giantExpression, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("maximum allowed size");
    }

    @Test
    void sandboxPublishForcesBoundsAndIncrementsVersion() {
        long v1 = service.currentVersion();
        RuleDefinition custom = new RuleDefinition("custom", "token_bucket", 50000L, 1000.0, 500L, 50000L, 1L, false, Priority.P1);

        CelPolicyService.PublishResult publishResult = service.publishSandbox("request.method == 'GET'", custom);
        assertThat(publishResult.success()).isTrue();
        assertThat(publishResult.version()).isGreaterThan(v1);

        // Verify bounds enforced
        RequestContext sandboxContext = new RequestContext("/demo", "GET", Map.of(), "1.2.3.4", "demo-sandbox", "FREE", Set.of(), "k1");
        ResolvedPolicy resolved = service.resolve(sandboxContext);
        assertThat(resolved.rule().capacity()).isLessThanOrEqualTo(100L);
        assertThat(resolved.rule().refillPerSec()).isLessThanOrEqualTo(50.0);
        assertThat(resolved.rule().windowMs()).isGreaterThanOrEqualTo(1000L);

        // Non-sandbox tenant is unaffected by sandbox rule
        RequestContext prodContext = new RequestContext("/demo", "GET", Map.of(), "1.2.3.4", "tenant-pro", "PRO", Set.of(), "k2");
        ResolvedPolicy prodResolved = service.resolve(prodContext);
        assertThat(prodResolved.ruleId()).isEqualTo("default-rule");
    }
}
