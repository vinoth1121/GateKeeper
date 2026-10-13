package io.gatekeeper.web;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hides /actuator/prometheus behind a 404 unless the caller presents a valid
 * X-Admin-Key, connects from loopback, or GATEKEEPER_PROM_PUBLIC=true is set
 * in the environment. /actuator/health is intentionally not guarded and stays
 * public for readiness probes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PrometheusGuardFilter extends OncePerRequestFilter {

    static final String PROMETHEUS_PATH = "/actuator/prometheus";
    static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final AdminKeyGuard adminKeyGuard;

    public PrometheusGuardFilter(AdminKeyGuard adminKeyGuard) {
        this.adminKeyGuard = adminKeyGuard;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROMETHEUS_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (adminKeyGuard.isValid(request) || isLoopback(request.getRemoteAddr()) || promPublic()) {
            chain.doFilter(request, response);
            return;
        }
        // Hide existence: answer as if the endpoint were not there at all.
        Jsons.write(response, 404, Jsons.object("error", "not_found"));
    }

    private boolean isLoopback(String remoteAddr) {
        return remoteAddr != null && LOOPBACK_ADDRESSES.contains(remoteAddr.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean promPublic() {
        return Boolean.parseBoolean(System.getenv("GATEKEEPER_PROM_PUBLIC"));
    }
}
