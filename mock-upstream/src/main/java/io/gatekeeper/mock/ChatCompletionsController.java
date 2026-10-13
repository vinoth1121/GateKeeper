package io.gatekeeper.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatCompletionsController {

    public record ChatMessage(String role, String content) {
    }

    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Integer max_tokens,
            Boolean stream) {
    }

    public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {
    }

    public record AssistantMessage(String role, String content) {
    }

    public record Choice(int index, AssistantMessage message, String finish_reason) {
    }

    public record ChatCompletionResponse(
            String id, String object, long created, String model, List<Choice> choices, Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(int index, Delta delta, String finish_reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chunk(String id, String object, long created, String model,
            List<ChunkChoice> choices, Usage usage) {
    }

    private static final String COMPLETION_ID = "chatcmpl-mock-upstream";
    private static final long CHUNK_DELAY_MS = 30;

    private final ChaosConfigService chaos;
    private final ObjectMapper objectMapper;

    public ChatCompletionsController(ChaosConfigService chaos, ObjectMapper objectMapper) {
        this.chaos = chaos;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/v1/chat/completions")
    public void completions(@RequestBody ChatCompletionRequest request,
            HttpServletResponse response) throws IOException, InterruptedException {
        if (Boolean.TRUE.equals(request.stream())) {
            stream(request, response);
        } else {
            respond(request, response);
        }
    }

    private void respond(ChatCompletionRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        chaos.applyLatency();
        String reply = reply(request);
        Usage usage = usage(request, reply);
        ChatCompletionResponse body = new ChatCompletionResponse(
                COMPLETION_ID, "chat.completion", Instant.now().getEpochSecond(),
                model(request),
                List.of(new Choice(0, new AssistantMessage("assistant", reply), "stop")),
                usage);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private void stream(ChatCompletionRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        chaos.applyLatency();
        String reply = reply(request);
        Usage usage = usage(request, reply);
        long created = Instant.now().getEpochSecond();
        String model = model(request);

        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Accel-Buffering", "no");
        OutputStream out = response.getOutputStream();
        response.setBufferSize(0);

        writeFrame(out, new Chunk(COMPLETION_ID, "chat.completion.chunk", created, model,
                List.of(new ChunkChoice(0, new Delta("assistant", null), null)), null));
        Thread.sleep(CHUNK_DELAY_MS);

        for (String word : ReplyGenerator.wordsOf(reply)) {
            writeFrame(out, new Chunk(COMPLETION_ID, "chat.completion.chunk", created, model,
                    List.of(new ChunkChoice(0, new Delta(null, word + " "), null)), null));
            Thread.sleep(CHUNK_DELAY_MS);
        }

        writeFrame(out, new Chunk(COMPLETION_ID, "chat.completion.chunk", created, model,
                List.of(), usage));
        writeFrame(out, "[DONE]");
        out.flush();
    }

    private void writeFrame(OutputStream out, Chunk chunk) throws IOException {
        writeFrame(out, objectMapper.writeValueAsString(chunk));
    }

    private void writeFrame(OutputStream out, String payload) throws IOException {
        out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String model(ChatCompletionRequest request) {
        return request.model() == null ? "mock-gpt" : request.model();
    }

    private String lastUserContent(ChatCompletionRequest request) {
        String last = "";
        for (ChatMessage message : request.messages()) {
            if ("user".equals(message.role())) {
                last = message.content();
            }
        }
        return last;
    }

    private String reply(ChatCompletionRequest request) {
        return ReplyGenerator.generate(lastUserContent(request), request.max_tokens());
    }

    private Usage usage(ChatCompletionRequest request, String reply) {
        int promptTokens = 0;
        for (ChatMessage message : request.messages()) {
            promptTokens += TokenEstimator.tokens(message.content());
        }
        int completionTokens = TokenEstimator.tokens(reply);
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}