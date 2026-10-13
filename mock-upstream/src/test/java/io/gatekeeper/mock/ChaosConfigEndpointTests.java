package io.gatekeeper.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChaosConfigEndpointTests {

    @Autowired
    TestRestTemplate rest;

    @AfterEach
    void resetChaos() {
        ChaosTestSupport.postChaosConfig(rest, 0, 0.0);
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void setClampsLowValues() {
        var response = rest.exchange("/chaos/config", HttpMethod.POST,
                json("{\"latencyMs\":-5,\"errorRate\":-1.0}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"latencyMs\":0,\"errorRate\":0.0}");
    }

    @Test
    void setClampsHighValues() {
        var response = rest.exchange("/chaos/config", HttpMethod.POST,
                json("{\"latencyMs\":99999,\"errorRate\":2.5}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"latencyMs\":10000,\"errorRate\":1.0}");

        var get = rest.getForEntity("/chaos/config", String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isEqualTo("{\"latencyMs\":10000,\"errorRate\":1.0}");
    }
}