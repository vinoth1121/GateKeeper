package io.gatekeeper.tenant;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XffSpoofingTest {

    private static MockHttpServletRequest requestWith(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void untrustedPeerIgnoresForwardedForCompletely() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"));
        ClientInfo info = resolver.resolve(requestWith("203.0.113.5", "1.2.3.4"));
        assertEquals("203.0.113.5", info.ip());
        assertFalse(info.xffUsed());
    }

    @Test
    void trustedPeerUsesRightmostUntrustedEntry() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1", "trusted-proxy-1"));
        ClientInfo info = resolver.resolve(requestWith("127.0.0.1", "1.2.3.4, trusted-proxy-1"));
        assertEquals("1.2.3.4", info.ip());
        assertTrue(info.xffUsed());
    }

    @Test
    void trustedPeerFallsBackToPeerWhenAllForwardedEntriesAreTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1", "10.0.0.1"));
        ClientInfo info = resolver.resolve(requestWith("127.0.0.1", "10.0.0.1, 127.0.0.1"));
        assertEquals("127.0.0.1", info.ip());
        assertFalse(info.xffUsed());
    }

    @Test
    void trustedPeerWithoutForwardedHeaderUsesPeer() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"));
        ClientInfo info = resolver.resolve(requestWith("127.0.0.1", null));
        assertEquals("127.0.0.1", info.ip());
        assertFalse(info.xffUsed());
    }

    @Test
    void trustedPeerSkipsEmptyEntriesWhileWalkingRightToLeft() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1"));
        ClientInfo info = resolver.resolve(requestWith("10.0.0.1", "1.2.3.4, , 10.0.0.1"));
        assertEquals("1.2.3.4", info.ip());
        assertTrue(info.xffUsed());
    }

    @Test
    void ipv4CidrTrustedProxyIsHonoured() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        ClientInfo info = resolver.resolve(requestWith("10.255.42.1", "1.2.3.4"));
        assertEquals("1.2.3.4", info.ip());
        assertTrue(info.xffUsed());
    }

    @Test
    void ipv4CidrOutsideRangeIsNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        ClientInfo info = resolver.resolve(requestWith("192.168.1.1", "1.2.3.4"));
        assertEquals("192.168.1.1", info.ip());
        assertFalse(info.xffUsed());
    }

    @Test
    void fullLengthCidrMatchesExactAddressOnly() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1/32"));
        assertTrue(resolver.resolve(requestWith("10.0.0.1", "1.2.3.4")).xffUsed());
        assertFalse(resolver.resolve(requestWith("10.0.0.2", "1.2.3.4")).xffUsed());
    }

    @Test
    void ipv6PeersMatchExactly() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("::1"));
        ClientInfo loopback = resolver.resolve(requestWith("::1", "1.2.3.4"));
        assertEquals("1.2.3.4", loopback.ip());
        assertTrue(loopback.xffUsed());
        ClientInfo other = resolver.resolve(requestWith("2001:db8::1", "1.2.3.4"));
        assertEquals("2001:db8::1", other.ip());
        assertFalse(other.xffUsed());
    }

    @Test
    void invalidTrustEntriesNeverMatch() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/33", "not-an-ip/xx", "999.999.999.999"));
        ClientInfo info = resolver.resolve(requestWith("10.0.0.1", "1.2.3.4"));
        assertEquals("10.0.0.1", info.ip());
        assertFalse(info.xffUsed());
    }

    @Test
    void emptyTrustListNeverUsesForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());
        ClientInfo info = resolver.resolve(requestWith("127.0.0.1", "1.2.3.4"));
        assertEquals("127.0.0.1", info.ip());
        assertFalse(info.xffUsed());
    }
}
