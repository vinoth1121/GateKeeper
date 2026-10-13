package io.gatekeeper.lease;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3 leasing limiter instances ("nodes") vs ONE central Redis bucket.
 * Policy: capacity 100, refill 20/s, lease <= 2 (window 100ms).
 * The bound must hold: total granted <= capacity + refill*elapsed
 * (+ 3 in-flight leases; the Lua grant itself is exact, so the real
 * overshoot vs capacity+refill should be ~0).
 */
@Testcontainers
class LeaseOvershootTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static final int CAPACITY = 100;
    static final int REFILL_PER_SEC = 20;
    static final int HAMMER_SECONDS = 3;

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
    void threeNodesStayWithinCentralBucketBounds() throws Exception {
        // leaseWindowMs=100 -> lease = ceil(20 * 0.1) = 2 (<= 2 as required)
        GateKeeperProperties props = LeaseTestSupport.properties(
                new GateKeeperProperties.Lease(true, 100, 100, 2000, 0.2, true),
                new GateKeeperProperties.Redis("redis://test", 300, 20, 50, 5000, 5));
        Node a = support.buildNode(props);
        Node b = support.buildNode(props);
        Node c = support.buildNode(props);
        assertThat(a.leases().leaseSize(spec())).isEqualTo(2);

        AtomicLong allowed = new AtomicLong();
        AtomicLong checks = new AtomicLong();
        AtomicLong perNodeA = new AtomicLong();
        AtomicLong perNodeB = new AtomicLong();
        AtomicLong perNodeC = new AtomicLong();
        long startNanos = System.nanoTime();
        long deadline = startNanos + TimeUnit.SECONDS.toNanos(HAMMER_SECONDS);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                Node node = i % 3 == 0 ? a : (i % 3 == 1 ? b : c);
                AtomicLong perNode = i % 3 == 0 ? perNodeA : (i % 3 == 1 ? perNodeB : perNodeC);
                pool.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        checks.incrementAndGet();
                        if (node.service().check("overshoot", "free", "kh", spec()).allowed()) {
                            allowed.incrementAndGet();
                            perNode.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
        }

        double elapsed = (System.nanoTime() - startNanos) / 1e9;
        long total = allowed.get();
        long theoretical = CAPACITY + (long) (REFILL_PER_SEC * elapsed);
        long bound = theoretical + 3 * 2 + 3; // + one in-flight lease per node + measurement slack
        System.out.println("REAL overshoot run: elapsed_s=" + String.format("%.3f", elapsed)
                + " total_granted=" + total
                + " checks=" + checks.get()
                + " per_node A/B/C=" + perNodeA.get() + "/" + perNodeB.get() + "/" + perNodeC.get()
                + " theoretical=" + theoretical
                + " bound=" + bound
                + " overshoot_vs_theoretical=" + (total - theoretical));
        System.out.println("local hit ratio (node A): " + String.format("%.3f", a.leases().hitRatio()));

        assertThat(total)
                .isLessThanOrEqualTo(bound)
                .isGreaterThanOrEqualTo((long) (theoretical * 0.6));

        a.leases().shutdown();
        b.leases().shutdown();
        c.leases().shutdown();
    }

    static LimitSpec spec() {
        return new LimitSpec("overshoot", LimitSpec.Algorithm.TOKEN_BUCKET,
                CAPACITY, (double) REFILL_PER_SEC, 0, 0, 1, false);
    }
}
