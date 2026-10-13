package io.gatekeeper.mock;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class ChaosConfigService {

    private final AtomicReference<ChaosConfig> state = new AtomicReference<>(ChaosConfig.ZERO);

    public ChaosConfig get() {
        return state.get();
    }

    public ChaosConfig set(Integer latencyMs, Double errorRate) {
        return state.updateAndGet(current -> ChaosConfig.clamped(latencyMs, errorRate));
    }

    public void applyLatency() throws InterruptedException {
        int latency = state.get().latencyMs();
        if (latency > 0) {
            Thread.sleep(latency);
        }
    }
}