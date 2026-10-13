package io.gatekeeper.limiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ClockEdgeTest {

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
    void exhaustedBucketDeniesThenRefills() throws Exception {
        RedisRateLimiterService service = support.service();
        LimitSpec spec = new LimitSpec("edge", LimitSpec.Algorithm.TOKEN_BUCKET,
                5, 1.0, 0, 0, 1, false);

        for (int i = 0; i < 5; i++) {
            assertThat(service.check("edge", "free", "kh", spec).allowed()).isTrue();
        }

        LimiterDecision denied = service.check("edge", "free", "kh", spec);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isGreaterThan(0);
        assertThat(denied.source()).isEqualTo(LimiterDecision.DecisionSource.REDIS);

        Thread.sleep(denied.retryAfterMs() + 80);

        LimiterDecision refilled = service.check("edge", "free", "kh", spec);
        assertThat(refilled.allowed()).isTrue();
    }
}
