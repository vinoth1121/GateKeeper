package io.gatekeeper.limiter;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KeyExpiryTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static RedisClientSupport support;

    @BeforeAll
    static void start() {
        support = RedisClientSupport.create(REDIS);
    }

    @AfterAll
    static void stop() {
        if (support != null) {
            support.close();
        }
    }

    @Test
    void liveKeysHavePositiveTtl() {
        RedisRateLimiterService service = support.service();

        service.check("ttl", "free", "kh", new LimitSpec("tb", LimitSpec.Algorithm.TOKEN_BUCKET,
                10, 1.0, 0, 0, 1, false));
        service.check("ttl", "free", "kh", new LimitSpec("sw", LimitSpec.Algorithm.SLIDING_WINDOW,
                0, 0, 60_000, 100, 1, false));

        long bucketPttl = support.connection().sync()
                .pttl(RedisRateLimiterService.bucketKey("ttl", "kh", "tb"));
        String zsetKey = RedisRateLimiterService.bucketKey("ttl", "kh", "sw");
        long zsetPttl = support.connection().sync().pttl(zsetKey);
        long counterPttl = support.connection().sync().pttl(zsetKey + ":c");

        assertThat(bucketPttl).isPositive();
        assertThat(zsetPttl).isPositive();
        assertThat(counterPttl).isPositive();
    }

    @Test
    void shortTtlZsetKeyDisappears() {
        RedisRateLimiterService service = support.service();
        long windowMs = 100;
        service.check("ttl", "free", "kh", new LimitSpec("short", LimitSpec.Algorithm.SLIDING_WINDOW,
                0, 0, windowMs, 10, 1, false));

        String zsetKey = RedisRateLimiterService.bucketKey("ttl", "kh", "short");
        assertThat(support.connection().sync().pttl(zsetKey)).isPositive();

        Awaitility.await("short-TTL zset key disappears")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> support.connection().sync().exists(zsetKey) == 0L);
    }
}
