package io.gatekeeper.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class UpstreamController {

    private final ChaosConfigService chaos;

    public UpstreamController(ChaosConfigService chaos) {
        this.chaos = chaos;
    }

    public record HelloResponse(String message, int upstreamLatencyMs) {
    }

    public record EchoResponse(String method, String path, String query, Map<String, String> headers) {
    }

    private static final List<String> ECHOED_HEADERS = List.of(
            "User-Agent", "Accept", "Accept-Language", "Content-Type", "Host",
            "X-Request-Id", "X-Forwarded-For", "X-Forwarded-Proto");

    @GetMapping("/hello")
    public ResponseEntity<HelloResponse> hello() throws InterruptedException {
        chaos.applyLatency();
        ChaosConfig config = chaos.get();
        if (ThreadLocalRandom.current().nextDouble() < config.errorRate()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new HelloResponse("hello from mock-upstream failed", config.latencyMs()));
        }
        return ResponseEntity.ok(new HelloResponse("hello from mock-upstream", config.latencyMs()));
    }

    @GetMapping("/echo")
    public EchoResponse echo(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : ECHOED_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return new EchoResponse(request.getMethod(), request.getRequestURI(), request.getQueryString(), headers);
    }
}