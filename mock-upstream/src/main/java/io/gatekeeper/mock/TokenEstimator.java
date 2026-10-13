package io.gatekeeper.mock;

public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * Deterministic token estimate, identical for stream and non-stream paths.
     *
     * TOKEN FORMULA:
     *   tokens(text) = max(ceil(chars(text) / 4), whitespaceWords(text))
     *
     * where chars(text) is the UTF-16 character count of the text and
     * whitespaceWords(text) is the number of whitespace-separated words
     * (0 for a null, empty, or all-whitespace text). The max() means short
     * wordy strings are counted per word, while longer text is counted at
     * roughly 4 characters per token.
     */
    public static int tokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chars = text.length();
        int words = whitespaceWords(text);
        return Math.max((int) Math.ceil(chars / 4.0), words);
    }

    /**
     * Number of whitespace-separated words; 0 for null/blank text.
     */
    public static int whitespaceWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }
}