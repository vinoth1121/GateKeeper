package io.gatekeeper.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.gatekeeper.config.GateKeeperProperties;

/**
 * Public demo chaos endpoints. Everything here is SIMULATED and labeled as
 * such: upstream faults are forwarded to the mock upstream's /chaos/config,
 * Redis faults toggle the local FaultInjector bean consulted by RedisGuard.
 */
@RestController
public class ChaosController {

    private final ChaosFaultInjector faultInjector;
    private final GateKeeperProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ChaosController(ChaosFaultInjector faultInjector, GateKeeperProperties properties) {
        this.faultInjector = faultInjector;
        this.properties = properties;
    }

    public record UpstreamChaosRequest(Integer latencyMs) {
    }

    public record RedisChaosRequest(String mode, Long latencyMs) {
    }

    @PostMapping(value = "/api/chaos/upstream", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> upstreamChaos(@RequestBody UpstreamChaosRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulated", true);
        Map<String, Object> upstream = new LinkedHashMap<>();
        try {
            int latency = request.latencyMs() == null ? 0 : Math.max(0, request.latencyMs());
            HttpRequest call = HttpRequest.newBuilder()
                    .uri(URI.create(properties.upstream().baseUrl() + "/chaos/config"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"latencyMs\":" + latency + "}"))
                    .build();
            HttpResponse<String> response = httpClient.send(call, HttpResponse.BodyHandlers.ofString());
            upstream.put("status", response.statusCode());
            upstream.put("latencyMs", latency);
        } catch (Exception e) {
            upstream.put("error", "upstream_unreachable");
        }
        body.put("upstream", upstream);
        return body;
    }

    @PostMapping(value = "/api/chaos/redis", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redisChaos(@RequestBody RedisChaosRequest request) {
        faultInjector.configure(request.mode(), request.latencyMs());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulated", true);
        body.put("redis", faultInjector.describe());
        return body;
    }

    @GetMapping(value = "/api/chaos/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulated", true);
        body.put("redis", faultInjector.describe());
        return body;
    }
}
