package io.gatekeeper.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer wiring: console static resources and the demo FaultInjector bean.
 *
 * The antrun copy-console execution copies the top-level console/ directory
 * into the jar under static/console at build time; the explicit resource
 * handler guarantees /console/** resolves regardless of the default static
 * resource configuration. The root "/" is served by the existing
 * static/index.html meta-refresh to /console/ and is kept as-is.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/**")
                .addResourceLocations("classpath:/static/console/");
    }

    /** Demo (simulated) Redis fault injector consulted by RedisGuard. */
    @Bean
    public ChaosFaultInjector gateKeeperFaultInjector() {
        return new ChaosFaultInjector();
    }
}
