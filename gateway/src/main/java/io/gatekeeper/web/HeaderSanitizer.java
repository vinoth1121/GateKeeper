package io.gatekeeper.web;

import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Hop-by-hop header helper. Per RFC 7230+ hop-by-hop headers must not be
 * forwarded by proxies. Spring Cloud Gateway (MVC) already strips most of
 * these when it writes proxied responses, but the servlet API has no
 * removeHeader, so this class enforces stripping at WRITE time: response
 * wrappers route every header mutation through {@link #isHopByHop} and drop
 * matches. That keeps our own JSON error responses clean and acts as a safe
 * guard even if a proxied path bypasses SCG's own sanitizing.
 */
public final class HeaderSanitizer {

    /** Lower-case hop-by-hop header names, never forwarded by the gateway. */
    public static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization");

    private HeaderSanitizer() {
    }

    public static boolean isHopByHop(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Writes the header only when it is not hop-by-hop. */
    public static void setHeaderSafe(HttpServletResponse response, String name, String value) {
        if (!isHopByHop(name) && value != null) {
            response.setHeader(name, value);
        }
    }

    public static void addHeaderSafe(HttpServletResponse response, String name, String value) {
        if (!isHopByHop(name) && value != null) {
            response.addHeader(name, value);
        }
    }
}
