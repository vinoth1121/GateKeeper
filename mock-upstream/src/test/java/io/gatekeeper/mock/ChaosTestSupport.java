package io.gatekeeper.mock;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

final class ChaosTestSupport {

    private ChaosTestSupport() {
    }

    static void postChaosConfig(TestRestTemplate rest, int latencyMs, double errorRate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"latencyMs\":" + latencyMs + ",\"errorRate\":" + errorRate + "}";
        rest.exchange("/chaos/config", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}