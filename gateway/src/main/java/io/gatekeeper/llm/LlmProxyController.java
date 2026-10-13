package io.gatekeeper.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.tenant.ApiKeyAuthenticationService;
import io.gatekeeper.tenant.ApiKeys;
import io.gatekeeper.tenant.Tenant;
import io.gatekeeper.tenant.Tier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LlmProxyController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int STREAM_CHUNK_BYTES = 8 * 1024;
    private static final String DONE_MARKER = "[DONE]";

    private final ApiKeyAuthenticationService authenticationService;
    private final TokenEstimator tokenEstimator;
    private final LlmMeteringService meteringService;
    private final LlmProperties llmProperties;
    private final GateKeeperProperties properties;

    public LlmProxyController(ApiKeyAuthenticationService authenticationService,
                              TokenEstimator tokenEstimator,
                              LlmMeteringService meteringService,
                              LlmProperties llmProperties,
                              GateKeeperProperties properties) {
        this.authenticationService = authenticationService;
        this.tokenEstimator = tokenEstimator;
        this.meteringService = meteringService;
        this.llmProperties = llmProperties;
        this.properties = properties;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatRequest(
            String model,
            List<Map<String, String>> messages,
            Integer max_tokens,
            Boolean stream) {
    }

    @PostMapping("/llm/v1/chat/completions")
    public void chatCompletions(@RequestBody ChatRequest request,
                                HttpServletRequest servletRequest,
                                HttpServletResponse servletResponse) throws IOException {
        String authorizationHeader = servletRequest.getHeader("Authorization");
        String apiKeyHeader = servletRequest.getHeader("X-API-Key");
        String rawKey = extractApiKey(authorizationHeader, apiKeyHeader);
        String keyHash = rawKey != null ? ApiKeys.sha256Hex(rawKey).substring(0, 8) : "00000000";
        // local/demo mode: no key resolves to a tenant, so serve the caller as a
        // built-in demo FREE tenant instead of rejecting outright.
        Tenant tenant = authenticationService.resolve(authorizationHeader, apiKeyHeader, servletRequest.getRemoteAddr())
                .orElseGet(() -> new Tenant("demo-local", Tier.FREE, Set.of(), "demo-local", keyHash));

        String prompt = promptOf(request);
        int maxTokens = request.max_tokens() != null ? request.max_tokens() : llmProperties.defaultMaxTokens();
        int estimated = tokenEstimator.estimatedTotal(prompt, maxTokens);
        int promptEstimate = tokenEstimator.estimatePromptTokens(prompt);

        LlmMeteringService.CheckResult rpm = meteringService.checkRpm(tenant.id(), keyHash);
        if (!rpm.allowed()) {
            writeRateLimited(servletResponse, rpm);
            return;
        }
        LlmMeteringService.CheckResult tpm = meteringService.reserveTpm(tenant.id(), keyHash, estimated);
        if (!tpm.allowed()) {
            writeRateLimited(servletResponse, tpm);
            return;
        }

        int[] actualTokens = {-1};
        try {
            forward(request, servletResponse, actualTokens);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!servletResponse.isCommitted()) {
                try {
                    servletResponse.sendError(HttpServletResponse.SC_BAD_GATEWAY);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!servletResponse.isCommitted()) {
                try {
                    servletResponse.sendError(HttpServletResponse.SC_BAD_GATEWAY);
                } catch (IOException ignored) {
                }
            }
        } finally {
            // Disconnect policy: reconcile with the observed usage tokens whenever the
            // upstream reported them; otherwise fall back to the prompt estimate.
            // Reservations that leak from connections abandoned before reconcile
            // self-heal via the reservation TTL in the TPM bucket.
            meteringService.reconcile(tenant.id(), keyHash, estimated, actualTokens[0] >= 0 ? actualTokens[0] : promptEstimate);
        }
    }

    private void forward(ChatRequest request, HttpServletResponse servletResponse, int[] actualTokens)
            throws IOException, InterruptedException {
        String body = MAPPER.writeValueAsString(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(properties.upstream().baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        boolean stream = Boolean.TRUE.equals(request.stream());
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        HttpResponse<InputStream> upstream = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = upstream.statusCode();
        if (status < 200 || status >= 300) {
            // Upstream rejected the call: pass its status and body through. No usage
            // was observed, so the finally block reconciles the prompt estimate.
            servletResponse.setStatus(status);
            servletResponse.setContentType(upstream.headers().firstValue("Content-Type").orElse("application/json"));
            try (InputStream in = upstream.body()) {
                in.transferTo(servletResponse.getOutputStream());
            }
            servletResponse.getOutputStream().flush();
            return;
        }
        if (stream) {
            servletResponse.setStatus(status);
            servletResponse.setContentType("text/event-stream");
            servletResponse.setHeader("X-Accel-Buffering", "no");
            SseUsageScanner scanner = new SseUsageScanner();
            byte[] buffer = new byte[STREAM_CHUNK_BYTES];
            try (InputStream in = upstream.body()) {
                OutputStream out = servletResponse.getOutputStream();
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                    scanner.feed(buffer, read);
                }
            }
            Integer usage = scanner.usageTokens();
            if (usage != null) {
                actualTokens[0] = usage;
            }
        } else {
            byte[] upstreamBody;
            try (InputStream in = upstream.body()) {
                upstreamBody = in.readAllBytes();
            }
            servletResponse.setStatus(status);
            servletResponse.setContentType("application/json");
            servletResponse.getOutputStream().write(upstreamBody);
            servletResponse.getOutputStream().flush();
            Integer usage = usageFrom(MAPPER.readTree(upstreamBody));
            if (usage != null) {
                actualTokens[0] = usage;
            }
        }
    }

    private void writeRateLimited(HttpServletResponse response, LlmMeteringService.CheckResult result)
            throws IOException {
        Map<String, Object> error = Map.of("error", Map.of(
                "type", "rate_limit_exceeded",
                "dimension", result.dimension(),
                "retry_after_seconds", result.retryAfterSeconds()));
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType("application/json");
        response.getOutputStream().write(MAPPER.writeValueAsBytes(error));
        response.getOutputStream().flush();
    }

    private static String promptOf(ChatRequest request) {
        if (request.messages() == null) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        for (Map<String, String> message : request.messages()) {
            if (message != null && message.get("content") != null) {
                prompt.append(message.get("content"));
            }
        }
        return prompt.toString();
    }

    private static String extractApiKey(String authorizationHeader, String apiKeyHeader) {
        if (authorizationHeader != null) {
            String trimmed = authorizationHeader.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                String candidate = trimmed.substring("Bearer ".length()).trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return apiKeyHeader.trim();
        }
        return null;
    }

    private static Integer usageFrom(JsonNode root) {
        JsonNode usage = root.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        if (usage.hasNonNull("total_tokens")) {
            return usage.get("total_tokens").asInt();
        }
        if (usage.hasNonNull("prompt_tokens") && usage.hasNonNull("completion_tokens")) {
            return usage.get("prompt_tokens").asInt() + usage.get("completion_tokens").asInt();
        }
        return null;
    }

    /**
     * Scans SSE bytes as they stream past, tracking at most one incomplete line at a
     * time so the final {@code data: {"usage":{...}}} frame can be observed without
     * ever buffering the response body. The [DONE] sentinel passes through untouched.
     */
    private static final class SseUsageScanner {

        private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        private Integer usageTokens;

        void feed(byte[] buffer, int length) {
            for (int i = 0; i < length; i++) {
                if (buffer[i] == '\n') {
                    processLine(currentLine.toString(StandardCharsets.UTF_8));
                    currentLine.reset();
                } else {
                    currentLine.write(buffer[i]);
                }
            }
        }

        Integer usageTokens() {
            return usageTokens;
        }

        private void processLine(String line) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                return;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty() || DONE_MARKER.equals(payload)) {
                return;
            }
            try {
                Integer usage = usageFrom(MAPPER.readTree(payload));
                if (usage != null) {
                    usageTokens = usage;
                }
            } catch (IOException ignored) {
            }
        }
    }
}
