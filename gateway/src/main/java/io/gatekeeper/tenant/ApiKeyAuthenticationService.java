package io.gatekeeper.tenant;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

public final class ApiKeyAuthenticationService {

    public static final long CACHE_MAXIMUM_SIZE = 10_000;
    public static final Duration CACHE_EXPIRY = Duration.ofMinutes(5);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_SCHEME = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationService.class);

    private final TenantRegistry tenantRegistry;
    private final ClientIpResolver clientIpResolver;
    private final Cache<String, Optional<Tenant>> tenantByHash;

    public ApiKeyAuthenticationService(TenantRegistry tenantRegistry, ClientIpResolver clientIpResolver) {
        this.tenantRegistry = Objects.requireNonNull(tenantRegistry, "tenantRegistry");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
        this.tenantByHash = Caffeine.newBuilder()
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .expireAfterWrite(CACHE_EXPIRY)
                .build();
    }

    public Optional<Tenant> resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        ClientInfo clientInfo = clientIpResolver.resolve(request);
        return resolve(request.getHeader(AUTHORIZATION_HEADER), request.getHeader(API_KEY_HEADER), clientInfo.ip());
    }

    public Optional<Tenant> resolve(String authorizationHeader, String apiKeyHeader, String clientIp) {
        String apiKey = extractApiKey(authorizationHeader, apiKeyHeader);
        if (apiKey == null) {
            log.debug("Client {} presented no API key", clientIp);
            return Optional.empty();
        }
        Optional<Tenant> tenant = tenantByHash.get(ApiKeys.sha256Hex(apiKey), tenantRegistry::byKeyHash);
        if (tenant.isEmpty()) {
            log.debug("Client {} presented an API key that matches no tenant", clientIp);
        }
        return tenant;
    }

    static String extractApiKey(String authorizationHeader, String apiKeyHeader) {
        if (authorizationHeader != null) {
            String trimmed = authorizationHeader.trim();
            if (trimmed.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
                String candidate = trimmed.substring(BEARER_SCHEME.length()).trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return apiKeyHeader.trim();
        }
        return null;
    }
}
