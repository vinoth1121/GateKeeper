package io.gatekeeper.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatCompletionsTests {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void resetChaos() {
        ChaosTestSupport.postChaosConfig(rest, 0, 0.0);
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void nonStreamReturnsUsagePerFormula() {
        String content = "Hello mock world";
        var response = rest.postForEntity("/v1/chat/completions", json(
                "{\"model\":\"mock-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\""
                        + content + "\"}],\"stream\":false}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        int expectedPrompt = TokenEstimator.tokens(content);
        int expectedCompletion = TokenEstimator.tokens(extractContent(body));
        assertThat(body).contains("\"object\":\"chat.completion\"");
        assertThat(body).contains("\"finish_reason\":\"stop\"");
        assertThat(body).contains("\"prompt_tokens\":" + expectedPrompt);
        assertThat(body).contains("\"completion_tokens\":" + expectedCompletion);
        assertThat(body).contains("\"total_tokens\":" + (expectedPrompt + expectedCompletion));
    }

    @Test
    void nonStreamTruncatesToMaxTokens() {
        var response = rest.postForEntity("/v1/chat/completions", json(
                "{\"model\":\"mock-gpt\",\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"alpha beta gamma delta epsilon zeta eta theta\"}],"
                        + "\"max_tokens\":5,\"stream\":false}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        int completion = extractCompletion(body);
        assertThat(completion).isPositive().isLessThanOrEqualTo(5);
        assertThat(body).contains("\"finish_reason\":\"stop\"");
    }

    private String extractContent(String body) {
        String marker = "\"content\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    private int extractCompletion(String body) {
        int index = body.indexOf("\"completion_tokens\":");
        int start = index + "\"completion_tokens\":".length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Integer.parseInt(body.substring(start, end));
    }
}