package io.gatekeeper.policy;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates client request and tenant identity context for CEL policy evaluation.
 */
public record RequestContext(
        String path,
        String method,
        Map<String, String> headers,
        String ip,
        String tenantId,
        String tier,
        Set<String> tags,
        String keyId) {

    public RequestContext {
        headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        tags = tags != null ? Collections.unmodifiableSet(tags) : Collections.emptySet();
    }
}
