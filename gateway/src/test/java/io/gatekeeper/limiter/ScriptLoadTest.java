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
class ScriptLoadTest {

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
    void checkSucceedsAfterScriptFlush() {
        RedisRateLimiterService service = support.service();
        LimitSpec spec = new LimitSpec("flush", LimitSpec.Algorithm.TOKEN_BUCKET,
                100, 10.0, 0, 0, 1, false);

        LimiterDecision first = service.check("flush", "free", "kh", spec);
        assertThat(first.allowed()).isTrue();

        // Wipe the script cache server-side: next EVALSHA must hit NOSCRIPT and recover.
        support.connection().sync().scriptFlush();

        LimiterDecision second = service.check("flush", "free", "kh", spec);
        assertThat(second.allowed()).isTrue();
        assertThat(second.source()).isEqualTo(LimiterDecision.DecisionSource.REDIS);

        // Sliding window script too.
        support.connection().sync().scriptFlush();
        LimitSpec sw = new LimitSpec("flushsw", LimitSpec.Algorithm.SLIDING_WINDOW,
                0, 0, 1000, 10, 1, false);
        assertThat(service.check("flush", "free", "kh", sw).allowed()).isTrue();
    }
}
