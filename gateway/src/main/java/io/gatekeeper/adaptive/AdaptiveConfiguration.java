package io.gatekeeper.adaptive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

import io.gatekeeper.config.GateKeeperProperties;

/**
 * Wires the adaptive concurrency layer: one {@link AdaptiveGateway} facade
 * owning a per-route Gradient2 limiter registry plus the priority shedder.
 * No synchronized anywhere; all limiter state is atomic.
 */
@Configuration
public class AdaptiveConfiguration {

    @Bean
    @ConditionalOnMissingBean(AdaptiveGateway.class)
    public AdaptiveGateway adaptiveGateway(GateKeeperProperties properties,
                                           ObjectProvider<MeterRegistry> meterRegistry) {
        return new AdaptiveGateway(properties.adaptive(), meterRegistry.getIfAvailable());
    }
}
