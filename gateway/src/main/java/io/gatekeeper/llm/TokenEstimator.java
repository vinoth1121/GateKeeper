package io.gatekeeper.llm;

import java.util.Objects;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estimates prompt token counts with the cl100k_base encoding when jtokkit is
 * available, falling back to a conservative chars/4 heuristic on any failure.
 */
public final class TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimator.class);

    private final Encoding encoding;

    public TokenEstimator() {
        this.encoding = resolveEncoding();
    }

    public int estimatePromptTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (encoding != null) {
            try {
                return encoding.countTokens(text);
            } catch (RuntimeException e) {
                log.debug("jtokkit countTokens failed, falling back to chars/4: {}", e.getMessage());
            }
        }
        return ceilDiv(text.length(), 4);
    }

    public int estimatedTotal(String prompt, int maxTokens) {
        return estimatePromptTokens(prompt) + Math.max(0, maxTokens);
    }

    private static Encoding resolveEncoding() {
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            return Objects.requireNonNull(registry.getEncoding(EncodingType.CL100K_BASE));
        } catch (RuntimeException | LinkageError e) {
            log.warn("jtokkit cl100k_base encoding unavailable, using chars/4 fallback: {}", e.getMessage());
            return null;
        }
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
