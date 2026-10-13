package io.gatekeeper.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.gatekeeper.limiter.LimitSpec;
import io.gatekeeper.limiter.LimiterDecision;
import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RateLimiterService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Meters LLM usage per tenant key: RPM via the shared sliding-window limiter and
 * TPM via a milli-token bucket reserved before the call and reconciled after it.
 * All checks are non-throwing; Redis failures surface as deny with retry hints
 * only when the limiter itself decides so.
 */
public class LlmMeteringService {

    public static final String RESERVE_SCRIPT = "llm_reserve";
    public static final String RECONCILE_SCRIPT = "llm_reconcile";
    public static final String RPM_RULE_ID = "llm-rpm";

    private static final String TPM_KEY_FORMAT = "gk:{t:%s}:k:%s:llm:tpm";
    private static final String TOKENS_TOTAL = "gatekeeper_llm_tokens_total";
    private static final String ESTIMATION_ERROR_GAUGE = "gatekeeper_llm_estimation_error";
    private static final double EMA_ALPHA = 0.2;
    private static final Logger log = LoggerFactory.getLogger(LlmMeteringService.class);

    private final RateLimiterService rateLimiter;
    private final LuaScriptManager scripts;
    private final LlmProperties properties;
    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicReference<Double> estimationErrorEma = new AtomicReference<>(0.0);

    public LlmMeteringService(RateLimiterService rateLimiter,
                              LuaScriptManager scripts,
                              LlmProperties properties,
                              MeterRegistry registry) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = registry;
        registerScript(RESERVE_SCRIPT, "/lua/reserve.lua");
        registerScript(RECONCILE_SCRIPT, "/lua/reconcile.lua");
        registerGauge();
    }

    public record CheckResult(boolean allowed, long retryAfterSeconds, String dimension) {
    }

    public CheckResult checkRpm(String tenantId, String keyHash) {
        LimitSpec spec = new LimitSpec(
                RPM_RULE_ID,
                LimitSpec.Algorithm.SLIDING_WINDOW,
                properties.rpmLimit(),
                0,
                60_000,
                properties.rpmLimit(),
                1,
                false);
        LimiterDecision decision = rateLimiter.check(tenantId, "llm", keyHash, spec);
        long retrySeconds = decision.allowed() ? 0 : ceilSeconds(decision.retryAfterMs());
        return new CheckResult(decision.allowed(), retrySeconds, "rpm");
    }

    public CheckResult reserveTpm(String tenantId, String keyHash, int estimatedTokens) {
        incrementTokens("estimated", Math.max(0, estimatedTokens));
        List<Object> result = scripts.eval(
                RESERVE_SCRIPT,
                List.of(tpmKey(tenantId, keyHash)),
                List.of(
                        String.valueOf(properties.tpmCapacity() * 1000L),
                        String.valueOf(milliRefillPerSec()),
                        String.valueOf(Math.max(0, estimatedTokens) * 1000L),
                        String.valueOf(properties.reservationTtlMs())));
        long allowed = longAt(result, 0);
        long retryAfterMs = longAt(result, 2);
        if (allowed == 1) {
            incrementTokens("reserved", Math.max(0, estimatedTokens));
        }
        long retrySeconds = allowed == 1 ? 0 : ceilSeconds(retryAfterMs);
        return new CheckResult(allowed == 1, retrySeconds, "tpm");
    }

    public void reconcile(String tenantId, String keyHash, int estimatedTokens, int actualTokens) {
        incrementTokens("actual", Math.max(0, actualTokens));
        long diff = (long) actualTokens - estimatedTokens;
        List<Object> result = scripts.eval(
                RECONCILE_SCRIPT,
                List.of(tpmKey(tenantId, keyHash)),
                List.of(
                        String.valueOf(properties.tpmCapacity() * 1000L),
                        String.valueOf(milliRefillPerSec()),
                        String.valueOf(diff * 1000L),
                        String.valueOf(properties.reservationTtlMs())));
        long refunded = longAt(result, 1);
        long charged = longAt(result, 2);
        if (refunded == 1) {
            incrementTokens("refunded", Math.max(0, -diff));
        }
        if (charged == 1) {
            incrementTokens("charged", Math.max(0, diff));
        }
        updateEstimationError(actualTokens, estimatedTokens);
    }

    private void updateEstimationError(int actualTokens, int estimatedTokens) {
        double error = Math.abs((double) actualTokens - estimatedTokens) / Math.max(1.0, actualTokens);
        estimationErrorEma.updateAndGet(current -> EMA_ALPHA * error + (1.0 - EMA_ALPHA) * current);
    }

    private void incrementTokens(String type, double amount) {
        if (registry != null && amount > 0) {
            counters.computeIfAbsent(type, t -> Counter.builder(TOKENS_TOTAL)
                    .description("LLM token counts by flow type")
                    .tag("type", t)
                    .register(registry)).increment(amount);
        }
    }

    private void registerGauge() {
        if (registry == null) {
            return;
        }
        Gauge.builder(ESTIMATION_ERROR_GAUGE, estimationErrorEma, AtomicReference::get)
                .description("EMA of relative LLM token estimation error")
                .register(registry);
    }

    private void registerScript(String name, String resourcePath) {
        try (InputStream in = LlmMeteringService.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing Lua resource on classpath: " + resourcePath);
            }
            scripts.registerScript(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Lua resource: " + resourcePath, e);
        }
    }

    private static String tpmKey(String tenantId, String keyHash) {
        return String.format(TPM_KEY_FORMAT, tenantId, keyHash);
    }

    private long milliRefillPerSec() {
        return (long) Math.max(0.0, properties.tpmRefillPerSec() * 1000.0);
    }

    private static long longAt(List<Object> result, int index) {
        if (result == null || index >= result.size() || result.get(index) == null) {
            return 0;
        }
        Object value = result.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private static long ceilSeconds(long millis) {
        if (millis <= 0) {
            return 1;
        }
        return (millis + 999) / 1000;
    }
}
