package io.gatekeeper.limiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SlidingWindowExactnessTest {

    private static final long WINDOW_MS = 1000;
    private static final long LIMIT = 50;

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
    void noSampledOneSecondWindowExceedsLimit() throws Exception {
        RedisRateLimiterService service = support.service();

        // Warm up connection, script cache and Redis on a separate rule.
        LimitSpec warm = new LimitSpec("warmup", LimitSpec.Algorithm.SLIDING_WINDOW,
                0, 0, WINDOW_MS, LIMIT, 1, false);
        for (int i = 0; i < 25; i++) {
            service.check("exact", "free", "kh", warm);
        }

        LimitSpec spec = new LimitSpec("exact", LimitSpec.Algorithm.SLIDING_WINDOW,
                0, 0, WINDOW_MS, LIMIT, 1, false);
        Queue<Long> allowedAt = new ConcurrentLinkedQueue<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                pool.submit(() -> {
                    while (System.nanoTime() < deadlineNanos) {
                        LimiterDecision decision = service.check("exact", "free", "kh", spec);
                        if (decision.allowed()) {
                            // Redis decision time (resetEpochMs - window), not the local
                            // return time, keeps window math free of transport jitter.
                            allowedAt.add(decision.resetEpochMs() - WINDOW_MS);
                        }
                    }
                    return null;
                });
            }
        }

        List<Long> stamps = new ArrayList<>(allowedAt);
        Collections.sort(stamps);
        System.out.println("allowed_total=" + stamps.size());
        assertThat(stamps.size()).isGreaterThanOrEqualTo((int) LIMIT);

        // Sample every 1-second sliding window at 1ms resolution over the timeline:
        // for each timestamp t, count entries in (t - WINDOW_MS, t].
        int worst = 0;
        int j = 0;
        for (int i = 0; i < stamps.size(); i++) {
            long end = stamps.get(i);
            while (stamps.get(j) <= end - WINDOW_MS) {
                j++;
            }
            worst = Math.max(worst, i - j + 1);
        }
        System.out.println("max_in_any_1s_window=" + worst);
        assertThat(worst).isLessThanOrEqualTo((int) LIMIT);
    }
}
