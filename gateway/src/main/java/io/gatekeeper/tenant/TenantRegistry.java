package io.gatekeeper.tenant;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TenantRegistry {

    private static final Charset HASH_CHARSET = StandardCharsets.US_ASCII;

    private final List<Tenant> tenants;
    private final Map<String, Tenant> tenantsById;

    public TenantRegistry(TenantProperties properties) {
        this(tenantsFrom(Objects.requireNonNull(properties, "properties").seeds()));
    }

    public TenantRegistry(Collection<Tenant> tenants) {
        List<Tenant> copy = List.copyOf(tenants);
        Map<String, Tenant> byId = new HashMap<>();
        Set<String> keyHashes = new HashSet<>();
        for (Tenant tenant : copy) {
            Tenant existing = byId.putIfAbsent(tenant.id(), tenant);
            if (existing != null) {
                throw new IllegalArgumentException("duplicate tenant id '" + tenant.id() + "' in tenant registry");
            }
            if (!keyHashes.add(tenant.keyHash().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "duplicate key hash shared by tenant '" + tenant.id() + "' and another tenant");
            }
        }
        this.tenants = copy;
        this.tenantsById = Map.copyOf(byId);
    }

    public List<Tenant> all() {
        return tenants;
    }

    public Optional<Tenant> byTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantsById.get(tenantId));
    }

    public Optional<Tenant> byKeyHash(String keyHash) {
        if (keyHash == null || keyHash.isBlank()) {
            return Optional.empty();
        }
        byte[] candidate = keyHash.trim().toLowerCase(Locale.ROOT).getBytes(HASH_CHARSET);
        for (Tenant tenant : tenants) {
            byte[] stored = tenant.keyHash().toLowerCase(Locale.ROOT).getBytes(HASH_CHARSET);
            if (MessageDigest.isEqual(stored, candidate)) {
                return Optional.of(tenant);
            }
        }
        return Optional.empty();
    }

    private static List<Tenant> tenantsFrom(List<TenantProperties.TenantSeed> seeds) {
        List<Tenant> result = new ArrayList<>(seeds.size());
        for (TenantProperties.TenantSeed seed : seeds) {
            result.add(new Tenant(seed.tenantId(), seed.tier(), seed.tags(), seed.keyId(), seed.keyHash()));
        }
        return result;
    }
}
