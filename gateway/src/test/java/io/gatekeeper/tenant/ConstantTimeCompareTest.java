package io.gatekeeper.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstantTimeCompareTest {

    private final TenantRegistry registry = new TenantRegistry(new TenantProperties(null));

    private static byte[] asciiHex(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void messageDigestIsEqualAcceptsIdenticalHashes() {
        for (Tenant tenant : registry.all()) {
            byte[] hash = asciiHex(tenant.keyHash());
            assertTrue(MessageDigest.isEqual(hash, hash.clone()));
        }
    }

    @Test
    void messageDigestIsEqualRejectsEqualLengthDifferingHashes() {
        List<byte[]> hashes = new ArrayList<>();
        for (Tenant tenant : registry.all()) {
            hashes.add(asciiHex(tenant.keyHash()));
        }
        for (byte[] left : hashes) {
            for (byte[] right : hashes) {
                if (left != right) {
                    assertFalse(MessageDigest.isEqual(left, right));
                }
            }
        }
    }

    @Test
    void messageDigestIsEqualRejectsDifferentLengthInputs() {
        byte[] hash = asciiHex(ApiKeys.sha256Hex("gk_demo_free_tenant"));
        assertEquals(64, hash.length);
        assertFalse(MessageDigest.isEqual(hash, Arrays.copyOf(hash, 63)));
        assertFalse(MessageDigest.isEqual(hash, Arrays.copyOf(hash, 32)));
        assertFalse(MessageDigest.isEqual(hash, asciiHex("too-short")));
    }

    @Test
    void registryLookupRejectsEqualLengthNearMissHash() {
        String realHash = ApiKeys.sha256Hex("gk_demo_free_tenant");
        char flipped = realHash.charAt(63) == '8' ? '9' : '8';
        String nearMiss = realHash.substring(0, 63) + flipped;
        assertEquals(realHash.length(), nearMiss.length());
        assertNotEquals(realHash, nearMiss);
        assertTrue(registry.byKeyHash(nearMiss).isEmpty());
    }

    @Test
    void registryLookupRejectsMalformedHashes() {
        assertTrue(registry.byKeyHash(null).isEmpty());
        assertTrue(registry.byKeyHash("").isEmpty());
        assertTrue(registry.byKeyHash("   ").isEmpty());
        assertTrue(registry.byKeyHash("gk_demo_free_tenant").isEmpty());
        assertTrue(registry.byKeyHash("3049766666daff6b6ab5b09c4cbfb7b2f6d7728e0acb12ba80d1aadb2cbae618a").isEmpty());
    }

    @Test
    void registryLookupFindsAllSeededTenantsByExactHash() {
        for (Tenant tenant : registry.all()) {
            assertEquals(tenant.id(), registry.byKeyHash(tenant.keyHash()).orElseThrow().id());
        }
    }

    @Test
    void registryLookupIsHexCaseInsensitive() {
        String upperCaseHash = ApiKeys.sha256Hex("gk_demo_sandbox").toUpperCase(Locale.ROOT);
        assertEquals("demo-sandbox", registry.byKeyHash(upperCaseHash).orElseThrow().id());
    }
}
