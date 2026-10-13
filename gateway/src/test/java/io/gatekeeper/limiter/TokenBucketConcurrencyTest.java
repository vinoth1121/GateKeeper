package io.gatekeeper.limiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TokenBucketConcurrencyTest {

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
    void hammeringOneBucketStaysWithinRefillBounds() throws Exception {
        RedisRateLimiterService service = support.service();
        LimitSpec spec = new LimitSpec("concur", LimitSpec.Algorithm.TOKEN_BUCKET,
                100, 20.0, 0, 0, 1, false);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicLong allowed = new AtomicLong();
        AtomicLong total = new AtomicLong();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                pool.submit(() -> {
                    while (System.nanoTime() < deadlineNanos) {
                        total.incrementAndGet();
                        if (service.check("concur", "free", "kh", spec).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
        }

        long allowedTotal = allowed.get();
        long theoreticalMax = 100 + 20 * 5;
        System.out.println("allowed_total=" + allowedTotal + " total_checks=" + total.get());
        assertThat(allowedTotal)
                .isLessThanOrEqualTo(theoreticalMax + 2)
                .isGreaterThanOrEqualTo((long) (theoreticalMax * 0.6));
    }
}
