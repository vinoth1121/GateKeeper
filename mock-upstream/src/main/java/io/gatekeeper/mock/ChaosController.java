package io.gatekeeper.mock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChaosController {

    private final ChaosConfigService chaos;

    public ChaosController(ChaosConfigService chaos) {
        this.chaos = chaos;
    }

    public record ChaosConfigRequest(Integer latencyMs, Double errorRate) {
    }

    @PostMapping("/chaos/config")
    public ChaosConfig set(@RequestBody ChaosConfigRequest request) {
        return chaos.set(request.latencyMs(), request.errorRate());
    }

    @GetMapping("/chaos/config")
    public ChaosConfig get() {
        return chaos.get();
    }
}