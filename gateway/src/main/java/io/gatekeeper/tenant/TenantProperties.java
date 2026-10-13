package io.gatekeeper.tenant;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatekeeper.security")
public record TenantProperties(List<TenantSeed> seeds) {

    public static final List<TenantSeed> DEFAULT_SEEDS = List.of(
            new TenantSeed("demo-free", Tier.FREE, Set.of("demo"), "demo-free-key-1",
                    "3049766666daff6b6ab5b09c4cbfb7b2f6d7728e0acb12ba80d1aadb2cbae618"),
            new TenantSeed("demo-pro", Tier.PRO, Set.of("demo"), "demo-pro-key-1",
                    "2387256ff58a4b45d7ab13d0846034d0cce76492068e2724e00da5ee2029283b"),
            new TenantSeed("demo-enterprise", Tier.ENTERPRISE, Set.of("demo"), "demo-enterprise-key-1",
                    "482f1545f80383de1e1789b0bf886fdab900e4f0f4a44469e2f6852c22b401fa"),
            new TenantSeed("demo-sandbox", Tier.FREE, Set.of("demo", "sandbox"), "demo-sandbox-key-1",
                    "7f7990ecb5b9abedcd0e10289fc59107ffd71ea0f9de48a6227b6aa6c4c05a8a"));

    public TenantProperties {
        seeds = (seeds == null || seeds.isEmpty()) ? DEFAULT_SEEDS : List.copyOf(seeds);
    }

    public record TenantSeed(String tenantId, Tier tier, Set<String> tags, String keyId, String keyHash) {

        public TenantSeed {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId must not be blank");
            }
            Objects.requireNonNull(tier, () -> "tier must not be null for tenant '" + tenantId + "'");
            if (keyHash == null || keyHash.isBlank()) {
                throw new IllegalArgumentException("keyHash must not be blank for tenant '" + tenantId + "'");
            }
            String trimmed = keyHash.trim();
            if (trimmed.startsWith("gk_")) {
                throw new IllegalArgumentException(
                        "refusing to seed plaintext API key as keyHash for tenant '" + tenantId
                                + "': store the SHA-256 hex digest of the key instead");
            }
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "keyHash must be a 64-character hex SHA-256 digest for tenant '" + tenantId + "'");
            }
            keyHash = normalized;
            keyId = (keyId == null || keyId.isBlank()) ? tenantId + "-key-1" : keyId.trim();
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }
}
