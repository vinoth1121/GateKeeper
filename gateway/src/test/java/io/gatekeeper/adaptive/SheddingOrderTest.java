package io.gatekeeper.adaptive;

import org.junit.jupiter.api.Test;

import io.gatekeeper.config.GateKeeperProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shedding order under congestion: P2 sheds first (429) above 50% of the
 * limit, P1 next (503) above 80%, P0 never - it waits up to p0WaitMs for a
 * freed slot, and is force-admitted past the wait rather than shed.
 */
class SheddingOrderTest {

    private static final GateKeeperProperties.Adaptive PINNED =
            new GateKeeperProperties.Adaptive(10, 10, 2.0, 0.2, 250, 0.5, 0.8);

    private final PriorityShedder shedder = new PriorityShedder(0.5, 0.8);
    private final AdaptiveGateway gateway = new AdaptiveGateway(PINNED, null);

    @Test
    void thresholdsShedP2BeforeP1AndNeverP0() {
        assertThat(shedder.check(5, 10, Priority.P2)).isEqualTo(ShedDecision.admitted());
        assertThat(shedder.check(6, 10, Priority.P2)).isEqualTo(new ShedDecision(false, 429, 1));
        assertThat(shedder.check(8, 10, Priority.P1)).isEqualTo(ShedDecision.admitted());
        assertThat(shedder.check(9, 10, Priority.P1)).isEqualTo(new ShedDecision(false, 503, 1));
        assertThat(shedder.check(10, 10, Priority.P0)).isEqualTo(ShedDecision.admitted());
        assertThat(shedder.check(10_000, 10, Priority.P0)).isEqualTo(ShedDecision.admitted());
    }

    @Test
    void drivingInflightHighShedsP2ThenP1WhileP0WaitsAndAcquires() throws Exception {
        String route = "orders";

        for (int i = 0; i < 5; i++) {
            assertThat(gateway.admit(route, Priority.P1).allowed()).isTrue();
        }
        assertThat(gateway.inflight(route)).isEqualTo(5);

        assertThat(gateway.admit(route, Priority.P2).allowed()).isTrue();
        assertThat(gateway.inflight(route)).isEqualTo(6);

        ShedDecision p2 = gateway.admit(route, Priority.P2);
        System.out.println("inflight=" + gateway.inflight(route) + " p2=" + p2);
        assertThat(p2.allowed()).isFalse();
        assertThat(p2.statusWhenShed()).isEqualTo(429);
        assertThat(p2.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

        assertThat(gateway.admit(route, Priority.P1).allowed()).isTrue();
        assertThat(gateway.admit(route, Priority.P1).allowed()).isTrue();
        assertThat(gateway.inflight(route)).isEqualTo(8);

        assertThat(gateway.admit(route, Priority.P1).allowed()).isTrue();
        assertThat(gateway.inflight(route)).isEqualTo(9);

        ShedDecision p1 = gateway.admit(route, Priority.P1);
        System.out.println("inflight=" + gateway.inflight(route) + " p1=" + p1);
        assertThat(p1.allowed()).isFalse();
        assertThat(p1.statusWhenShed()).isEqualTo(503);
        assertThat(p1.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

        assertThat(gateway.admit(route, Priority.P0).allowed()).isTrue();
        assertThat(gateway.inflight(route)).isEqualTo(10);

        long startNanos = System.nanoTime();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gateway.release(route, Priority.P1);
        });
        releaser.start();
        ShedDecision p0 = gateway.admit(route, Priority.P0);
        long waitedMs = (System.nanoTime() - startNanos) / 1_000_000;
        releaser.join();
        System.out.println("p0=" + p0 + " waitedMs=" + waitedMs
                + " inflight=" + gateway.inflight(route));
        assertThat(p0.allowed()).isTrue();
        assertThat(waitedMs).isGreaterThanOrEqualTo(70);
        assertThat(gateway.inflight(route)).isEqualTo(10);
    }

    @Test
    void p0IsNeverShedEvenWhenNoSlotFreesWithinItsWait() {
        AdaptiveGateway tight = new AdaptiveGateway(
                new GateKeeperProperties.Adaptive(2, 2, 2.0, 0.2, 40, 0.5, 0.8), null);
        assertThat(tight.admit("checkout", Priority.P0).allowed()).isTrue();
        assertThat(tight.admit("checkout", Priority.P0).allowed()).isTrue();
        assertThat(tight.inflight("checkout")).isEqualTo(2);

        long startNanos = System.nanoTime();
        ShedDecision decision = tight.admit("checkout", Priority.P0);
        long waitedMs = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println("p0-over-limit=" + decision + " waitedMs=" + waitedMs
                + " inflight=" + tight.inflight("checkout"));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.statusWhenShed()).isZero();
        assertThat(waitedMs).isGreaterThanOrEqualTo(35);
        assertThat(tight.inflight("checkout")).isEqualTo(3);
    }
}
