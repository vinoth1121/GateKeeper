package io.gatekeeper.lease;

import java.lang.management.ManagementFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RateLimiterService;
import io.gatekeeper.limiter.RedisRateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.gatekeeper.resilience.FaultInjector;
import io.gatekeeper.resilience.NodeHeartbeat;
import io.gatekeeper.resilience.RedisGuard;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Two-tier local token leasing + circuit-breaker wiring, active unless
 * gatekeeper.lease.enabled=false. The leasing RateLimiterService is @Primary
 * over the plain RedisRateLimiterService; strict rules delegate to a wrapped
 * RedisRateLimiterService through the RedisGuard.
 */
@Configuration
@ConditionalOnProperty(name = "gatekeeper.lease.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseConfiguration {

    /** No-op FaultInjector so the guard has a hook even without the web chaos bean. */
    @Bean
    @ConditionalOnMissingBean(FaultInjector.class)
    public FaultInjector noopFaultInjector() {
        return new FaultInjector() {
        };
    }

    @Bean(destroyMethod = "close")
    public RedisGuard redisGuard(GateKeeperProperties properties, GateKeeperMeters meters,
            ObjectProvider<FaultInjector> faultInjector, ObjectProvider<MeterRegistry> meterRegistry) {
        return new RedisGuard(properties, meters, faultInjector.getIfAvailable(), meterRegistry.getIfAvailable());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public NodeHeartbeat nodeHeartbeat(StatefulRedisConnection<String, String> connection, RedisGuard guard,
            LuaScriptManager scripts) {
        String nodeId = ManagementFactory.getRuntimeMXBean().getName(); // host@pid
        return new NodeHeartbeat(connection, guard, scripts, "default", nodeId);
    }

    @Bean(destroyMethod = "shutdown")
    public LocalLeaseManager localLeaseManager(RedisGuard guard, LuaScriptManager scripts,
            GateKeeperProperties properties, GateKeeperMeters meters, ObjectProvider<MeterRegistry> registry) {
        return new LocalLeaseManager(guard, scripts, properties.lease(), meters, registry.getIfAvailable());
    }

    @Bean
    @Primary
    public RateLimiterService leasingRateLimiterService(LuaScriptManager scripts, GateKeeperMeters meters,
            LocalLeaseManager leases, RedisGuard guard, GateKeeperProperties properties) {
        return new LeasingRateLimiterService(
                new RedisRateLimiterService(scripts, meters), leases, guard, meters, properties.lease());
    }
}
