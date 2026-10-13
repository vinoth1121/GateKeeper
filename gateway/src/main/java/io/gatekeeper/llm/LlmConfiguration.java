package io.gatekeeper.llm;

import io.gatekeeper.limiter.LuaScriptManager;
import io.gatekeeper.limiter.RateLimiterService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean
    public TokenEstimator tokenEstimator() {
        return new TokenEstimator();
    }

    @Bean
    public LlmMeteringService llmMeteringService(RateLimiterService rateLimiter,
                                                  LuaScriptManager scripts,
                                                  LlmProperties properties,
                                                  ObjectProvider<MeterRegistry> meterRegistry) {
        return new LlmMeteringService(rateLimiter, scripts, properties, meterRegistry.getIfAvailable());
    }
}
