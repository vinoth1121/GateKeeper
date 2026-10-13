package io.gatekeeper.tenant;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAuthConfiguration {

    public static final String TRUSTED_PROXIES_PROPERTY = "gatekeeper.trusted-proxies";

    private static final Bindable<List<String>> TRUSTED_PROXIES = Bindable.listOf(String.class);

    @Bean
    public TenantRegistry tenantRegistry(TenantProperties tenantProperties) {
        return new TenantRegistry(tenantProperties);
    }

    @Bean
    public ClientIpResolver clientIpResolver(Environment environment) {
        List<String> trustedProxies = Binder.get(environment)
                .bind(TRUSTED_PROXIES_PROPERTY, TRUSTED_PROXIES)
                .orElseGet(List::of);
        return new ClientIpResolver(trustedProxies);
    }

    @Bean
    public ApiKeyAuthenticationService apiKeyAuthenticationService(TenantRegistry tenantRegistry,
            ClientIpResolver clientIpResolver) {
        return new ApiKeyAuthenticationService(tenantRegistry, clientIpResolver);
    }
}
