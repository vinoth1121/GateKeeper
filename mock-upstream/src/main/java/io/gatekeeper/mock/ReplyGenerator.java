package io.gatekeeper.mock;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic reply generator: builds a ~25 word reply by cycling the words
 * of the last user message (falling back to a fixed lorem-style paragraph when
 * the input has no words), then truncates it so that tokens(reply) <= maxTokens
 * whenever maxTokens is provided. The same reply is used for stream and
 * non-stream responses, so both paths report identical completion token counts.
 */
public final class ReplyGenerator {

    private static final int REPLY_WORDS = 25;

    private static final String[] FALLBACK = {
            "the", "mock", "upstream", "service", "acknowledges", "your", "request",
            "and", "returns", "this", "deterministic", "reply", "for", "gatekeeper",
            "congestion", "lab", "testing", "purposes", "only", "hello", "from", "upstream"
    };

    private ReplyGenerator() {
    }

    public static String generate(String lastUserContent, Integer maxTokens) {
        String[] words = lastUserContent == null || lastUserContent.isBlank()
                ? FALLBACK
                : lastUserContent.strip().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REPLY_WORDS; i++) {
            String candidate = sb.isEmpty() ? words[i % words.length]
                    : sb + " " + words[i % words.length];
            if (maxTokens != null && TokenEstimator.tokens(candidate) > maxTokens) {
                break;
            }
            sb.setLength(0);
            sb.append(candidate);
        }
        return sb.toString();
    }

    public static List<String> wordsOf(String reply) {
        List<String> words = new ArrayList<>();
        if (reply != null && !reply.isBlank()) {
            for (String word : reply.strip().split("\\s+")) {
                words.add(word);
            }
        }
        return words;
    }
}