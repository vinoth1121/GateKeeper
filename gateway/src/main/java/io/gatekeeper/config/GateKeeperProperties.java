package io.gatekeeper.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gatekeeper")
public record GateKeeperProperties(
        @DefaultValue List<String> trustedProxies,
        @DefaultValue Upstream upstream,
        @DefaultValue Lease lease,
        @DefaultValue Policy policy,
        @DefaultValue Limits limits,
        @DefaultValue Adaptive adaptive,
        @DefaultValue Redis redis) {

    public record Upstream(@DefaultValue("http://localhost:8081") String baseUrl) {
    }

    public record Lease(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100") int maxLease,
            @DefaultValue("1000") long leaseWindowMs,
            @DefaultValue("2000") long leaseTtlMs,
            @DefaultValue("0.2") double lowWaterMark,
            @DefaultValue("true") boolean strictBypass) {
    }

    public record Policy(
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("4096") int maxExpressionBytes,
            @DefaultValue("64") int maxRules,
            @DefaultValue("64") int maxCostLimit,
            @DefaultValue("demo-sandbox") String sandboxTenant,
            @DefaultValue("600000") long sandboxResetMs) {
    }

    public record Limits(
            @DefaultValue("1048576") int requestBodyMaxBytes,
            @DefaultValue("64") int headerCountMax,
            @DefaultValue("16384") int headerSizeMaxBytes) {
    }

    public record Adaptive(
            @DefaultValue("4") int minLimit,
            @DefaultValue("1024") int maxLimit,
            @DefaultValue("2.0") double tolerance,
            @DefaultValue("0.2") double smoothing,
            @DefaultValue("250") long p0WaitMs,
            @DefaultValue("0.5") double shedThresholdP2,
            @DefaultValue("0.8") double shedThresholdP1) {
    }

    public record Redis(
            @DefaultValue("redis://localhost:6379") String url,
            @DefaultValue("300") int callTimeoutMs,
            @DefaultValue("20") int breakerSlidingWindowSize,
            @DefaultValue("50") int breakerFailureRateThreshold,
            @DefaultValue("5000") long breakerWaitMs,
            @DefaultValue("5") int breakerHalfOpenCalls) {
    }
}
