package io.gatekeeper.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/**
 * Validates the X-Admin-Key header against the GATEKEEPER_ADMIN_KEY
 * environment variable (development default: dev-admin-key-change-me).
 * Comparison is constant-time via MessageDigest.isEqual.
 */
@Component
public class AdminKeyGuard {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    public static final String DEFAULT_ADMIN_KEY = "dev-admin-key-change-me";

    private final byte[] expected;

    public AdminKeyGuard() {
        this(System.getenv("GATEKEEPER_ADMIN_KEY"));
    }

    AdminKeyGuard(String adminKey) {
        String key = adminKey == null || adminKey.isBlank() ? DEFAULT_ADMIN_KEY : adminKey;
        this.expected = key.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(HttpServletRequest request) {
        String presented = request == null ? null : request.getHeader(ADMIN_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
