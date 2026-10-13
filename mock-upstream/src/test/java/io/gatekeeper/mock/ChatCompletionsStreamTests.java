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
class ChatCompletionsStreamTests {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void resetChaos() {
        ChaosTestSupport.postChaosConfig(rest, 0, 0.0);
    }

    @Test
    void streamEmitsOrderedFrames() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                "{\"model\":\"mock-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"stream me a reply\"}],"
                        + "\"stream\":true}", headers);
        var response = rest.exchange("/v1/chat/completions", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        String body = response.getBody();
        assertThat(body).isNotNull();

        int roleFrame = body.indexOf("\"delta\":{\"role\":\"assistant\"}");
        int firstContent = body.indexOf("\"delta\":{\"content\":");
        int usageFrame = body.indexOf("\"usage\":{");
        int done = body.indexOf("data: [DONE]");

        assertThat(roleFrame).isGreaterThanOrEqualTo(0);
        assertThat(firstContent).isGreaterThan(roleFrame);
        assertThat(usageFrame).isGreaterThan(firstContent);
        assertThat(done).isGreaterThan(usageFrame);

        int contentChunks = countOccurrences(body, "\"delta\":{\"content\":");
        assertThat(contentChunks).isGreaterThanOrEqualTo(5);

        assertThat(body).contains("\"object\":\"chat.completion.chunk\"");
        assertThat(body).contains("\"prompt_tokens\":");
        assertThat(body).contains("\"total_tokens\":");
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}