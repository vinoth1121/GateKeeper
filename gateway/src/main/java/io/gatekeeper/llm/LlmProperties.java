package io.gatekeeper.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gatekeeper.llm")
public record LlmProperties(
        @DefaultValue("20") int rpmLimit,
        @DefaultValue("10000") int tpmCapacity,
        @DefaultValue("166.0") double tpmRefillPerSec,
        @DefaultValue("120") int defaultMaxTokens,
        @DefaultValue("30000") long reservationTtlMs) {

    public LlmProperties {
        if (rpmLimit <= 0) {
            throw new IllegalArgumentException("gatekeeper.llm.rpm-limit must be positive");
        }
        if (tpmCapacity <= 0) {
            throw new IllegalArgumentException("gatekeeper.llm.tpm-capacity must be positive");
        }
        if (tpmRefillPerSec <= 0) {
            throw new IllegalArgumentException("gatekeeper.llm.tpm-refill-per-sec must be positive");
        }
        if (defaultMaxTokens < 0) {
            throw new IllegalArgumentException("gatekeeper.llm.default-max-tokens must not be negative");
        }
        if (reservationTtlMs <= 0) {
            throw new IllegalArgumentException("gatekeeper.llm.reservation-ttl-ms must be positive");
        }
    }
}
