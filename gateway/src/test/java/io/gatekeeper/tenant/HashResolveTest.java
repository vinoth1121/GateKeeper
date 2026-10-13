package io.gatekeeper.tenant;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashResolveTest {

    private static final String CLIENT_IP = "198.51.100.7";

    private final TenantRegistry registry = new TenantRegistry(new TenantProperties(null));
    private final ApiKeyAuthenticationService service =
            new ApiKeyAuthenticationService(registry, new ClientIpResolver(List.of()));

    @Test
    void demoFreeKeyResolvesToFreeTenant() {
        Optional<Tenant> tenant = service.resolve("Bearer gk_demo_free_tenant", null, CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-free", tenant.get().id());
        assertEquals(Tier.FREE, tenant.get().tier());
        assertEquals("demo-free-key-1", tenant.get().keyId());
        assertTrue(tenant.get().tags().contains("demo"));
    }

    @Test
    void demoProKeyResolvesToProTenant() {
        Optional<Tenant> tenant = service.resolve("Bearer gk_demo_pro_tenant", null, CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-pro", tenant.get().id());
        assertEquals(Tier.PRO, tenant.get().tier());
    }

    @Test
    void demoEnterpriseKeyResolvesToEnterpriseTenant() {
        Optional<Tenant> tenant = service.resolve("Bearer gk_demo_ent_tenant", null, CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-enterprise", tenant.get().id());
        assertEquals(Tier.ENTERPRISE, tenant.get().tier());
    }

    @Test
    void sandboxKeyResolvesToSandboxTenant() {
        Optional<Tenant> tenant = service.resolve("Bearer gk_demo_sandbox", null, CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-sandbox", tenant.get().id());
        assertEquals(Tier.FREE, tenant.get().tier());
        assertTrue(tenant.get().tags().contains("sandbox"));
    }

    @Test
    void xApiKeyHeaderIsAccepted() {
        Optional<Tenant> tenant = service.resolve(null, "gk_demo_pro_tenant", CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-pro", tenant.get().id());
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverXApiKeyHeader() {
        Optional<Tenant> tenant = service.resolve("Bearer gk_demo_pro_tenant", "gk_demo_free_tenant", CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-pro", tenant.get().id());
    }

    @Test
    void nonBearerAuthorizationFallsBackToXApiKeyHeader() {
        Optional<Tenant> tenant = service.resolve("Basic dXNlcjpwYXNz", "gk_demo_sandbox", CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-sandbox", tenant.get().id());
    }

    @Test
    void headerWhitespaceIsTolerated() {
        Optional<Tenant> tenant = service.resolve("Bearer   gk_demo_pro_tenant  ", null, CLIENT_IP);
        assertTrue(tenant.isPresent());
        assertEquals("demo-pro", tenant.get().id());
    }

    @Test
    void missingOrMalformedCredentialsResolveToEmpty() {
        assertTrue(service.resolve(null, null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("Bearer ", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("Basic dXNlcjpwYXNz", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("   ", "   ", CLIENT_IP).isEmpty());
    }

    @Test
    void wrongKeysResolveToEmpty() {
        assertTrue(service.resolve("Bearer gk_wrong_key", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("Bearer gk_demo_free_tenantX", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("Bearer gk_demo_free", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve(null, "gk_demo_pro_tenant2", CLIENT_IP).isEmpty());
    }

    @Test
    void keysAreCaseSensitive() {
        assertTrue(service.resolve("Bearer GK_DEMO_FREE_TENANT", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve("Bearer Gk_Demo_Free_Tenant", null, CLIENT_IP).isEmpty());
        assertTrue(service.resolve(null, "GK_DEMO_SANDBOX", CLIENT_IP).isEmpty());
        assertTrue(service.resolve(null, "Gk_Demo_Sandbox", CLIENT_IP).isEmpty());
    }

    @Test
    void seededHashesMatchFreshlyComputedSha256() {
        Map<String, String> keyToSeededHash = Map.of(
                "gk_demo_free_tenant", "3049766666daff6b6ab5b09c4cbfb7b2f6d7728e0acb12ba80d1aadb2cbae618",
                "gk_demo_pro_tenant", "2387256ff58a4b45d7ab13d0846034d0cce76492068e2724e00da5ee2029283b",
                "gk_demo_ent_tenant", "482f1545f80383de1e1789b0bf886fdab900e4f0f4a44469e2f6852c22b401fa",
                "gk_demo_sandbox", "7f7990ecb5b9abedcd0e10289fc59107ffd71ea0f9de48a6227b6aa6c4c05a8a");
        keyToSeededHash.forEach((key, seededHash) -> assertEquals(seededHash, ApiKeys.sha256Hex(key)));
        for (TenantProperties.TenantSeed seed : new TenantProperties(null).seeds()) {
            assertTrue(keyToSeededHash.containsValue(seed.keyHash()),
                    "seed hash for tenant '" + seed.tenantId() + "' must be one of the documented demo key hashes");
        }
    }

    @Test
    void registryResolvesEachDemoKeyByHash() {
        Map<String, String> keyToTenantId = Map.of(
                "gk_demo_free_tenant", "demo-free",
                "gk_demo_pro_tenant", "demo-pro",
                "gk_demo_ent_tenant", "demo-enterprise",
                "gk_demo_sandbox", "demo-sandbox");
        keyToTenantId.forEach((key, tenantId) ->
                assertEquals(tenantId, registry.byKeyHash(ApiKeys.sha256Hex(key)).orElseThrow().id()));
    }

    @Test
    void repeatedResolvesThroughCacheStayConsistent() {
        Optional<Tenant> first = service.resolve("Bearer gk_demo_ent_tenant", null, CLIENT_IP);
        Optional<Tenant> second = service.resolve("Bearer gk_demo_ent_tenant", null, "203.0.113.99");
        assertEquals("demo-enterprise", first.orElseThrow().id());
        assertEquals("demo-enterprise", second.orElseThrow().id());
    }

    @Test
    void resolveFromHttpServletRequestEndToEnd() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(CLIENT_IP);
        request.addHeader("Authorization", "Bearer gk_demo_ent_tenant");
        Optional<Tenant> tenant = service.resolve(request);
        assertTrue(tenant.isPresent());
        assertEquals("demo-enterprise", tenant.get().id());
        assertEquals(Tier.ENTERPRISE, tenant.get().tier());

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        unauthenticated.setRemoteAddr(CLIENT_IP);
        assertTrue(service.resolve(unauthenticated).isEmpty());
    }
}
