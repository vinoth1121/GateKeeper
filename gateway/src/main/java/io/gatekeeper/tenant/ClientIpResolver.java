package io.gatekeeper.tenant;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpResolver {

    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int IPV4_OCTETS = 4;

    private final List<String> trustedProxies;

    public ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    public ClientInfo resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return resolve(request.getRemoteAddr(), request.getHeader(FORWARDED_FOR_HEADER));
    }

    public ClientInfo resolve(String remoteAddr, String forwardedFor) {
        String peer = remoteAddr == null ? "" : remoteAddr.trim();
        if (!isTrusted(peer)) {
            return new ClientInfo(peer, false);
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return new ClientInfo(peer, false);
        }
        String[] entries = forwardedFor.split(",");
        for (int i = entries.length - 1; i >= 0; i--) {
            String entry = entries[i].trim();
            if (entry.isEmpty() || isTrusted(entry)) {
                continue;
            }
            return new ClientInfo(entry, true);
        }
        return new ClientInfo(peer, false);
    }

    private boolean isTrusted(String ip) {
        for (String proxy : trustedProxies) {
            if (proxy == null || proxy.isBlank()) {
                continue;
            }
            if (matches(ip, proxy.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String ip, String pattern) {
        if (pattern.indexOf('/') >= 0 && pattern.indexOf(':') < 0) {
            return matchesIpv4Cidr(ip, pattern);
        }
        return pattern.equals(ip);
    }

    private static boolean matchesIpv4Cidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        long network = toIpv4Long(cidr.substring(0, slash));
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        long address = toIpv4Long(ip);
        if (network < 0 || address < 0 || prefixLength < 0 || prefixLength > 32) {
            return false;
        }
        long mask = prefixLength == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        return (address & mask) == (network & mask);
    }

    private static long toIpv4Long(String ip) {
        if (ip == null) {
            return -1L;
        }
        String[] octets = ip.split("\\.");
        if (octets.length != IPV4_OCTETS) {
            return -1L;
        }
        long value = 0L;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return -1L;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return -1L;
                }
            }
            int segment = Integer.parseInt(octet);
            if (segment > 255) {
                return -1L;
            }
            value = (value << 8) | segment;
        }
        return value & 0xFFFFFFFFL;
    }
}
