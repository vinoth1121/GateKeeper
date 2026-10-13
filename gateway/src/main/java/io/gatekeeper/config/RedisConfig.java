package io.gatekeeper.config;

import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RateLimiterService;
import io.gatekeeper.limiter.RedisRateLimiterService;
import io.gatekeeper.metrics.GateKeeperMeters;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single shared multiplexed Lettuce connection and the limiter
 * beans built on top of it. No synchronized anywhere; Lettuce's shared
 * connection is thread-safe and virtual-thread friendly.
 */
@Configuration
public class RedisConfig implements DisposableBean {

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    @Bean(destroyMethod = "")
    public StatefulRedisConnection<String, String> redisConnection(
            ObjectProvider<GateKeeperProperties> properties) {
        GateKeeperProperties props = properties.getIfAvailable();
        String url = props != null ? props.redis().url() : null;
        if (url == null || url.isBlank()) {
            url = System.getenv("GATEKEEPER_REDIS_URL");
        }
        if (url == null || url.isBlank()) {
            url = "redis://localhost:6379";
        }
        try {
            client = RedisClient.create(RedisURI.create(url));
            client.setOptions(ClientOptions.builder().autoReconnect(true).build());
            connection = client.connect();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(RedisConfig.class)
                    .warn("Initial Redis connection to {} failed (continuing in degraded mode): {}", url, e.getMessage());
            connection = null;
        }
        return connection;
    }

    @Bean
    @ConditionalOnMissingBean(GateKeeperMeters.class)
    public GateKeeperMeters gateKeeperMeters(ObjectProvider<MeterRegistry> meterRegistry) {
        return new GateKeeperMeters(meterRegistry.getIfAvailable());
    }

    @Bean
    public LuaScriptManager luaScriptManager(StatefulRedisConnection<String, String> connection,
                                             GateKeeperMeters meters) {
        return new LuaScriptManager(connection, meters);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiterService.class)
    public RateLimiterService rateLimiterService(LuaScriptManager scripts, GateKeeperMeters meters) {
        return new RedisRateLimiterService(scripts, meters);
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
