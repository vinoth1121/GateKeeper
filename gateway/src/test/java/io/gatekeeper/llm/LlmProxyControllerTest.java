package io.gatekeeper.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.tenant.ApiKeyAuthenticationService;
import io.gatekeeper.tenant.ApiKeys;
import io.gatekeeper.tenant.ClientIpResolver;
import io.gatekeeper.tenant.Tenant;
import io.gatekeeper.tenant.TenantRegistry;
import io.gatekeeper.tenant.Tier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProxyControllerTest {

    private static final String API_KEY = "test-key";
    private static final String PROMPT = "Hello gatekeeper";
    private static final byte[] SSE = sseBody();

    private static HttpServer server;
    private static StubMeteringService metering;
    private static LlmProxyController controller;
    private static final TokenEstimator estimator = new TokenEstimator();
    private static volatile boolean truncate;

    @BeforeAll
    static void startUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", LlmProxyControllerTest::handle);
        server.start();
        int port = server.getAddress().getPort();
        metering = new StubMeteringService(new LlmProperties(20, 10000, 166.0, 120, 30000));
        controller = new LlmProxyController(
                new ApiKeyAuthenticationService(
                        new TenantRegistry(List.of(
                                new Tenant("t1", Tier.PRO, Set.of(), "k1", ApiKeys.sha256Hex(API_KEY)))),
                        new ClientIpResolver(List.of())),
                estimator,
                metering,
                new LlmProperties(20, 10000, 166.0, 120, 30000),
                new GateKeeperProperties(
                        List.of(),
                        new GateKeeperProperties.Upstream("http://127.0.0.1:" + port),
                        new GateKeeperProperties.Lease(true, 100, 1000, 2000, 0.2, true),
                        new GateKeeperProperties.Policy(2000, 4096, 64, 64, "demo-sandbox", 600000),
                        new GateKeeperProperties.Limits(1048576, 64, 16384),
                        new GateKeeperProperties.Adaptive(4, 1024, 2.0, 0.2, 250, 0.5, 0.8),
                        new GateKeeperProperties.Redis("redis://localhost:6379", 300, 20, 50, 5000, 5)));
    }

    @AfterAll
    static void stopUpstream() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetState() {
        truncate = false;
        metering.denyTpm = false;
        metering.calls.clear();
        metering.lastEstimated.set(-1);
        metering.lastActual.set(-1);
    }

    @Test
    void streamPassthroughPreservesChunkOrderAndReconcilesObservedUsage() throws Exception {
        MockHttpServletResponse response = post(new LlmProxyController.ChatRequest(
                "gpt-4o", List.of(Map.of("role", "user", "content", PROMPT)), 50, true));
        assertEquals(200, response.getStatus());
        assertEquals("text/event-stream", response.getContentType());
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertArrayEquals(SSE, response.getContentAsByteArray());
        assertTrue(metering.calls.contains("reconcile"));
        assertEquals(30, metering.lastActual.get());
        assertEquals(estimator.estimatedTotal(PROMPT, 50), metering.lastEstimated.get());
    }

    @Test
    void tpmDenialReturns429WithDimensionAndRetryAfter() throws Exception {
        metering.denyTpm = true;
        MockHttpServletResponse response = post(new LlmProxyController.ChatRequest(
                "gpt-4o", List.of(Map.of("role", "user", "content", "Hi")), 10, false));
        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"type\":\"rate_limit_exceeded\""));
        assertTrue(body.contains("\"dimension\":\"tpm\""));
        assertTrue(body.contains("\"retry_after_seconds\":5"));
        assertFalse(metering.calls.contains("reconcile"));
    }

    @Test
    void outputFailureStillReconcilesWithPromptEstimateFallback() throws Exception {
        truncate = true;
        MockHttpServletResponse response = post(new LlmProxyController.ChatRequest(
                "gpt-4o", List.of(Map.of("role", "user", "content", PROMPT)), 50, true));
        assertTrue(metering.calls.contains("reconcile"));
        assertEquals(estimator.estimatePromptTokens(PROMPT), metering.lastActual.get());
        assertEquals(estimator.estimatedTotal(PROMPT, 50), metering.lastEstimated.get());
    }

    private static MockHttpServletResponse post(LlmProxyController.ChatRequest request) throws IOException {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/llm/v1/chat/completions");
        servletRequest.addHeader("Authorization", "Bearer " + API_KEY);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        controller.chatCompletions(request, servletRequest, servletResponse);
        return servletResponse;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        if (truncate) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(SSE, 0, 64);
            out.flush();
            out.close();
        } else {
            exchange.sendResponseHeaders(200, SSE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(SSE);
            }
        }
        exchange.close();
    }

    private static byte[] sseBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"chunk").append(i).append("\"}}]}\n\n");
        }
        sb.append("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}\n\n");
        sb.append("data: [DONE]\n\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final class StubMeteringService extends LlmMeteringService {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger lastEstimated = new AtomicInteger(-1);
        final AtomicInteger lastActual = new AtomicInteger(-1);
        volatile boolean denyTpm;

        StubMeteringService(LlmProperties properties) {
            super((tenantId, tier, keyHash, spec) -> null, new LuaScriptManager(null, null), properties, null);
        }

        @Override
        public CheckResult checkRpm(String tenantId, String keyHash) {
            return new CheckResult(true, 0, "rpm");
        }

        @Override
        public CheckResult reserveTpm(String tenantId, String keyHash, int estimatedTokens) {
            calls.add("reserve");
            return new CheckResult(!denyTpm, 5, "tpm");
        }

        @Override
        public void reconcile(String tenantId, String keyHash, int estimatedTokens, int actualTokens) {
            calls.add("reconcile");
            lastEstimated.set(estimatedTokens);
            lastActual.set(actualTokens);
        }
    }
}
