package com.example.gpsengine.config;

import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.OsrmRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration activating either FakeRoutingProvider or OsrmRoutingProvider
 * based on the configured routing.provider property.
 */
@Configuration
public class RoutingConfiguration {

    private final String provider;

    public RoutingConfiguration(@Value("${routing.provider:fake}") String provider) {
        this.provider = provider != null ? provider.trim().toLowerCase() : "fake";
    }

    @PostConstruct
    public void validate() {
        if (!"fake".equals(provider) && !"osrm".equals(provider)) {
            throw new IllegalArgumentException(
                    "Unknown routing provider: '" + provider + "'. Allowed values are 'fake' or 'osrm'."
            );
        }
    }

    @Bean
    @ConditionalOnProperty(name = "routing.provider", havingValue = "fake", matchIfMissing = true)
    public RoutingProvider fakeRoutingProvider(
            @Value("${routing.fake.detour-factor:1.3}") double detourFactor) {
        return new FakeRoutingProvider(detourFactor);
    }

    @Bean
    @ConditionalOnProperty(name = "routing.provider", havingValue = "osrm")
    public RoutingProvider osrmRoutingProvider(
            @Value("${routing.osrm.base-url:http://localhost:5000}") String baseUrl,
            @Value("${routing.osrm.api-key:}") String apiKey,
            @Value("${routing.osrm.connect-timeout-millis:2000}") int connectTimeoutMillis,
            @Value("${routing.osrm.request-timeout-millis:3000}") int requestTimeoutMillis,
            @Autowired(required = false) ObjectMapper objectMapper) {
        ObjectMapper mapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        return new OsrmRoutingProvider(baseUrl, apiKey, connectTimeoutMillis, requestTimeoutMillis, mapper);
    }
}

