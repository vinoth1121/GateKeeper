package io.gatekeeper.tenant;

import java.util.Objects;
import java.util.Set;

public record Tenant(String id, Tier tier, Set<String> tags, String keyId, String keyHash) {

    public Tenant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
        Objects.requireNonNull(tier, () -> "tier must not be null for tenant '" + id + "'");
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank for tenant '" + id + "'");
        }
        if (keyHash == null || keyHash.isBlank()) {
            throw new IllegalArgumentException("keyHash must not be blank for tenant '" + id + "'");
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
