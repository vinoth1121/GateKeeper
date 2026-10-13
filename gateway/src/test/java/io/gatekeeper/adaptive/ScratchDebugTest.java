package io.gatekeeper.adaptive;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ScratchDebugTest {

    @Test
    void trace() {
        Gradient2Limiter l = new Gradient2Limiter(4, 1024, 2.0, 0.2);
        long rtt = TimeUnit.MILLISECONDS.toNanos(50);
        System.out.println("rtt=" + rtt);
        for (int i = 0; i < 10; i++) {
            l.onSample(rtt, false);
            System.out.println("i=" + i + " short=" + l.shortRttNanos()
                    + " long=" + l.longRttNanos() + " limit=" + l.currentLimit());
        }
    }
}
