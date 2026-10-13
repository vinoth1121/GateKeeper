package io.gatekeeper.resilience;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads Lua script resources from the classpath (same convention as the
 * limiter's built-in scripts under /lua).
 */
public final class LuaScripts {

    private LuaScripts() {
    }

    public static String read(String resourcePath) {
        try (InputStream in = LuaScripts.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing Lua resource on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Lua resource: " + resourcePath, e);
        }
    }
}
