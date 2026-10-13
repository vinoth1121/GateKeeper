package io.gatekeeper.limiter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.gatekeeper.metrics.GateKeeperMeters;
import org.testcontainers.containers.GenericContainer;

/**
 * Plain (non-Spring) wiring of the limiter against a Testcontainers Redis.
 */
final class RedisClientSupport implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisRateLimiterService service;

    private RedisClientSupport(RedisClient client, StatefulRedisConnection<String, String> connection,
                               RedisRateLimiterService service) {
        this.client = client;
        this.connection = connection;
        this.service = service;
    }

    static RedisClientSupport create(GenericContainer<?> redis) {
        RedisClient client = RedisClient.create(
                RedisURI.builder().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).build());
        StatefulRedisConnection<String, String> connection = client.connect();
        GateKeeperMeters meters = new GateKeeperMeters(null);
        RedisRateLimiterService service =
                new RedisRateLimiterService(new LuaScriptManager(connection, meters), meters);
        return new RedisClientSupport(client, connection, service);
    }

    RedisRateLimiterService service() {
        return service;
    }

    StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
