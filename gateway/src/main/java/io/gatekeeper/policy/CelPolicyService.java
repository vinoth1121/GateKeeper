package io.gatekeeper.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import io.gatekeeper.adaptive.Priority;
import io.gatekeeper.config.GateKeeperProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Production implementation of PolicyService using Google's CEL (Common Expression Language).
 * Compiles and typechecks expressions at publish time, validates positions, and provides
 * a sandboxed evaluation environment.
 */
@Service
public class CelPolicyService implements PolicyService {

    private static final Logger log = LoggerFactory.getLogger(CelPolicyService.class);

    private final GateKeeperProperties properties;
    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final AtomicLong version = new AtomicLong(1L);
    private final List<CompiledRule> activeRules = new CopyOnWriteArrayList<>();

    public record CompiledRule(
            String id,
            RuleDefinition definition,
            CelRuntime.Program program,
            String expression,
            boolean isSandbox) {
    }

    public record ValidationResult(boolean valid, Boolean matched, String error, Integer position) {
    }

    public record PublishResult(boolean success, long version, String error, Integer position) {
    }

    public CelPolicyService(GateKeeperProperties properties) {
        this.properties = properties;
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("request", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("tenant", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("keyId", SimpleType.STRING)
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    }

    @Override
    public long currentVersion() {
        return version.get();
    }

    @Override
    public RuleDefinition defaultRule() {
        return RuleDefinition.defaultRule();
    }

    @Override
    public ResolvedPolicy resolve(RequestContext context) {
        long currentVer = version.get();
        if (context == null) {
            return new ResolvedPolicy("default-rule", defaultRule(), currentVer);
        }

        Map<String, Object> vars = buildEvaluationVars(context);
        for (CompiledRule rule : activeRules) {
            if (rule.isSandbox() && !properties.policy().sandboxTenant().equals(context.tenantId())) {
                continue;
            }
            try {
                Object evalResult = rule.program().eval(vars);
                if (Boolean.TRUE.equals(evalResult)) {
                    return new ResolvedPolicy(rule.id(), rule.definition(), currentVer);
                }
            } catch (Exception e) {
                log.debug("CEL evaluation failed for rule {}: {}", rule.id(), e.getMessage());
            }
        }
        return new ResolvedPolicy("default-rule", defaultRule(), currentVer);
    }

    public ValidationResult dryRun(String expression, Map<String, Object> sampleVars) {
        if (expression == null || expression.isBlank()) {
            return new ValidationResult(false, null, "expression cannot be blank", 0);
        }
        int maxBytes = properties.policy().maxExpressionBytes();
        if (expression.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            return new ValidationResult(false, null, "expression exceeds maximum allowed size of " + maxBytes + " bytes", 0);
        }

        CelValidationResult result = compiler.compile(expression);
        if (result.hasError()) {
            CelIssue firstIssue = result.getErrors().isEmpty() ? null : result.getErrors().get(0);
            String message = firstIssue != null ? firstIssue.getMessage() : result.getErrorString();
            int pos = (firstIssue != null && firstIssue.getSourceLocation() != null)
                    ? firstIssue.getSourceLocation().getColumn()
                    : 0;
            return new ValidationResult(false, null, message, pos);
        }

        try {
            CelAbstractSyntaxTree ast = result.getAst();
            CelRuntime.Program program = runtime.createProgram(ast);
            Map<String, Object> vars = sampleVars != null ? sampleVars : buildDefaultSampleVars();
            Object evalResult = program.eval(vars);
            boolean matched = Boolean.TRUE.equals(evalResult);
            return new ValidationResult(true, matched, null, null);
        } catch (Exception e) {
            return new ValidationResult(false, null, "Evaluation error: " + e.getMessage(), 0);
        }
    }

    public PublishResult publishSandbox(String expression, RuleDefinition rule) {
        ValidationResult validation = dryRun(expression, null);
        if (!validation.valid()) {
            return new PublishResult(false, version.get(), validation.error(), validation.position());
        }

        RuleDefinition bounded = boundSandboxRule(rule != null ? rule : defaultRule());
        try {
            CelValidationResult compileResult = compiler.compile(expression);
            CelRuntime.Program program = runtime.createProgram(compileResult.getAst());
            String ruleId = "sandbox-" + System.currentTimeMillis();
            CompiledRule compiled = new CompiledRule(ruleId, bounded, program, expression, true);
            activeRules.add(0, compiled);
            long newVer = version.incrementAndGet();
            return new PublishResult(true, newVer, null, null);
        } catch (Exception e) {
            return new PublishResult(false, version.get(), e.getMessage(), 0);
        }
    }

    public PublishResult publishAdmin(String ruleId, String expression, RuleDefinition rule) {
        ValidationResult validation = dryRun(expression, null);
        if (!validation.valid()) {
            return new PublishResult(false, version.get(), validation.error(), validation.position());
        }

        try {
            CelValidationResult compileResult = compiler.compile(expression);
            CelRuntime.Program program = runtime.createProgram(compileResult.getAst());
            String id = (ruleId != null && !ruleId.isBlank()) ? ruleId : "admin-" + System.currentTimeMillis();
            RuleDefinition def = rule != null ? rule : defaultRule();
            CompiledRule compiled = new CompiledRule(id, def, program, expression, false);
            activeRules.add(0, compiled);
            long newVer = version.incrementAndGet();
            return new PublishResult(true, newVer, null, null);
        } catch (Exception e) {
            return new PublishResult(false, version.get(), e.getMessage(), 0);
        }
    }

    public List<Map<String, Object>> activeRuleSummaries() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CompiledRule rule : activeRules) {
            list.add(Map.of(
                    "id", rule.id(),
                    "expression", rule.expression(),
                    "algorithm", rule.definition().algorithm(),
                    "capacity", rule.definition().capacity(),
                    "isSandbox", rule.isSandbox()
            ));
        }
        return list;
    }

    private RuleDefinition boundSandboxRule(RuleDefinition input) {
        long maxCapacity = 100L;
        double maxRefill = 50.0;
        long capacity = Math.min(Math.max(1L, input.capacity()), maxCapacity);
        double refill = Math.min(Math.max(0.1, input.refillPerSec()), maxRefill);
        long windowMs = Math.max(1000L, input.windowMs());
        long max = Math.min(Math.max(1L, input.max()), maxCapacity);
        long cost = Math.max(1L, Math.min(input.cost(), capacity));
        return new RuleDefinition(input.id(), input.algorithm(), capacity, refill, windowMs, max, cost, false, Priority.P1);
    }

    private Map<String, Object> buildEvaluationVars(RequestContext context) {
        return Map.of(
                "request", Map.of(
                        "path", context.path() != null ? context.path() : "",
                        "method", context.method() != null ? context.method() : "",
                        "headers", context.headers() != null ? context.headers() : Map.of(),
                        "ip", context.ip() != null ? context.ip() : ""
                ),
                "tenant", Map.of(
                        "id", context.tenantId() != null ? context.tenantId() : "",
                        "tier", context.tier() != null ? context.tier() : "",
                        "tags", context.tags() != null ? List.copyOf(context.tags()) : List.of()
                ),
                "keyId", context.keyId() != null ? context.keyId() : ""
        );
    }

    private Map<String, Object> buildDefaultSampleVars() {
        return Map.of(
                "request", Map.of(
                        "path", "/demo/hello",
                        "method", "GET",
                        "headers", Map.of(),
                        "ip", "127.0.0.1"
                ),
                "tenant", Map.of(
                        "id", properties.policy().sandboxTenant(),
                        "tier", "FREE",
                        "tags", List.of()
                ),
                "keyId", "demo"
        );
    }
}
