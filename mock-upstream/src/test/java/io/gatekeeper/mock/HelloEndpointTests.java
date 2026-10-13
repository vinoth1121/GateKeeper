package io.gatekeeper.mock;

import org.junit.jupiter.api.BeforeEach;
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
class HelloEndpointTests {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void resetChaos() {
        ChaosTestSupport.postChaosConfig(rest, 0, 0.0);
    }

    @Test
    void helloReturns200WithJson() {
        var response = rest.getForEntity("/hello", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).contains("hello from mock-upstream");
        assertThat(response.getBody()).contains("\"upstreamLatencyMs\":0");
    }
}