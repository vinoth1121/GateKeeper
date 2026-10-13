package io.gatekeeper.lease;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RateLimiterService;
import io.gatekeeper.limiter.RedisRateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.gatekeeper.resilience.RedisGuard;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Plain (non-Spring) wiring of the lease/resilience stack against a
 * Testcontainers Redis (or any host/port, e.g. a Toxiproxy upstream).
 */
public final class LeaseTestSupport implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final LuaScriptManager scripts;
    private final GateKeeperMeters meters;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private LeaseTestSupport(RedisClient client, StatefulRedisConnection<String, String> connection,
            LuaScriptManager scripts, GateKeeperMeters meters) {
        this.client = client;
        this.connection = connection;
        this.scripts = scripts;
        this.meters = meters;
    }

    public static LeaseTestSupport create(String host, int port) {
        RedisClient client = RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
        StatefulRedisConnection<String, String> connection = client.connect();
        GateKeeperMeters meters = new GateKeeperMeters(null);
        LuaScriptManager scripts = new LuaScriptManager(connection, meters);
        return new LeaseTestSupport(client, connection, scripts, meters);
    }

    public static LeaseTestSupport create(GenericContainer<?> redis) {
        return create(redis.getHost(), redis.getMappedPort(6379));
    }

    /** One "gateway node": guard + lease manager + leasing limiter, sharing this Redis. */
    public Node buildNode(GateKeeperProperties properties) {
        GateKeeperMeters nodeMeters = new GateKeeperMeters(registry);
        RedisGuard guard = new RedisGuard(properties, nodeMeters, null, registry);
        LocalLeaseManager leases =
                new LocalLeaseManager(guard, scripts, properties.lease(), nodeMeters, registry);
        RateLimiterService strict = new RedisRateLimiterService(scripts, nodeMeters);
        LeasingRateLimiterService service =
                new LeasingRateLimiterService(strict, leases, guard, nodeMeters, properties.lease());
        return new Node(guard, leases, service);
    }

    public StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    public MeterRegistry registry() {
        return registry;
    }

    public record Node(RedisGuard guard, LocalLeaseManager leases, LeasingRateLimiterService service) {
    }

    public static GateKeeperProperties properties(GateKeeperProperties.Lease lease,
            GateKeeperProperties.Redis redis) {
        return new GateKeeperProperties(
                List.of(),
                new GateKeeperProperties.Upstream("http://localhost:8081"),
                lease,
                new GateKeeperProperties.Policy(2000L, 4096, 64, 64, "demo-sandbox", 600000L),
                new GateKeeperProperties.Limits(1048576, 64, 16384),
                new GateKeeperProperties.Adaptive(4, 1024, 2.0, 0.2, 250L, 0.5, 0.8),
                redis);
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
