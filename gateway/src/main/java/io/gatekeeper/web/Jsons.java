package io.gatekeeper.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Minimal JSON writer for filter-level error responses. The filter must
 * answer errors as JSON only (never a raw servlet error page), so these
 * helpers keep responses uniform without pulling Jackson into filter code.
 */
public final class Jsons {

    private Jsons() {
    }

    /** Escapes the minimal set of characters that can appear in our messages. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<', '>', '&' -> sb.append(" ").append((char) ('a' + (c % 26)));
                default -> {
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String object(String key, String value) {
        return "{\"" + key + "\":\"" + escape(value) + "\"}";
    }

    public static void write(HttpServletResponse response, int status, String json) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(json.getBytes(StandardCharsets.UTF_8).length);
        response.getWriter().write(json);
        response.flushBuffer();
    }
}
