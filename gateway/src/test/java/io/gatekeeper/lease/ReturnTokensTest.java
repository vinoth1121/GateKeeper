package io.gatekeeper.lease;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.gatekeeper.config.GateKeeperProperties;
import io.gatekeeper.limiter.LimitSpec;
import io.gatekeeper.lease.LeaseTestSupport.Node;
import io.gatekeeper.limiter.RedisRateLimiterService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Leftover local lease tokens must be returned to the central bucket when a
 * lease expires (sweeper) and on shutdown, capped at capacity.
 */
@Testcontainers
class ReturnTokensTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LeaseTestSupport support;

    @BeforeAll
    static void start() {
        support = LeaseTestSupport.create(REDIS);
    }

    @AfterAll
    static void stop() {
        if (support != null) {
            support.close();
        }
    }

    @Test
    void expiredLeaseReturnsLeftoverTokensToRedisCappedAtCapacity() {
        // capacity 20, refill 20/s -> lease = 20; consume 1 -> 19 leftover to return.
        GateKeeperProperties props = LeaseTestSupport.properties(
                new GateKeeperProperties.Lease(true, 100, 1000, 400, 0.2, true),
                new GateKeeperProperties.Redis("redis://test", 300, 20, 50, 5000, 5));
        Node node = support.buildNode(props);
        String key = RedisRateLimiterService.bucketKey("return", "kh", "return-rule");
        LimitSpec spec = new LimitSpec("return-rule", LimitSpec.Algorithm.TOKEN_BUCKET,
                20, 20.0, 0, 0, 1, false);

        // Consume one token: fills a 20-token lease, leaves 19 locally, 0 centrally.
        assertThat(node.service().check("return", "free", "kh", spec).allowed()).isTrue();
        long centralBefore = centralTokens(key);
        System.out.println("ReturnTokens REAL: central tokens after lease grant = " + centralBefore);

        // Idle past leaseTtl (400ms) + sweeper interval (200ms).
        await("leftover returned to redis")
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> centralTokens(key) > centralBefore);

        long centralAfter = centralTokens(key);
        System.out.println("ReturnTokens REAL: central tokens after sweep = " + centralAfter
                + " (capped at capacity 20000 milli)");
        double expired = support.registry()
                .get("gatekeeper.lease.expired.tokens.total")
                .functionCounter()
                .count();
        System.out.println("ReturnTokens REAL: gatekeeper_lease_expired_tokens_total = " + expired);
        assertThat(expired).isGreaterThanOrEqualTo(1000.0); // at least one whole token returned
        assertThat(centralAfter).isLessThanOrEqualTo(20000L); // capped at capacity (milli-tokens)

        // Shutdown path (@PreDestroy): leftover from a fresh node goes back to Redis.
        Node node2 = support.buildNode(props);
        assertThat(node2.service().check("return", "free", "kh2", spec).allowed()).isTrue();
        String key2 = RedisRateLimiterService.bucketKey("return", "kh2", "return-rule");
        long before2 = centralTokens(key2);
        node2.leases().shutdown();
        long after2 = centralTokens(key2);
        System.out.println("ReturnTokens REAL: shutdown returned " + (after2 - before2) + " milli-tokens");
        assertThat(after2).isGreaterThan(before2);

        node.leases().shutdown();
    }

    long centralTokens(String key) {
        String v = support.connection().sync().hget(key, "tokens");
        return v == null ? 0 : Long.parseLong(v);
    }
}
