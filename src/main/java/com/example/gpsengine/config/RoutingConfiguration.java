package com.example.gpsengine.config;

import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.CircuitBreakerRoutingProvider;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.OsrmRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;

/**
 * Spring configuration wiring routing providers, telemetry counters, circuit breaker,
 * and caching decorators.
 */
@Configuration
public class RoutingConfiguration {

    private final String provider;
    private final boolean cacheEnabled;
    private final long maxEntries;
    private final long ttlHours;
    private final double cellSizeDegrees;
    private final int failureThreshold;
    private final long openSeconds;

    public RoutingConfiguration(
            @Value("${routing.provider:fake}") String provider,
            @Value("${routing.cache.enabled:true}") boolean cacheEnabled,
            @Value("${routing.cache.max-entries:100000}") long maxEntries,
            @Value("${routing.cache.ttl-hours:24}") long ttlHours,
            @Value("${routing.cache.cell-size-degrees:0.001}") double cellSizeDegrees,
            @Value("${routing.breaker.failure-threshold:5}") int failureThreshold,
            @Value("${routing.breaker.open-seconds:30}") long openSeconds) {
        this.provider = provider != null ? provider.trim().toLowerCase() : "fake";
        this.cacheEnabled = cacheEnabled;
        this.maxEntries = maxEntries;
        this.ttlHours = ttlHours;
        this.cellSizeDegrees = cellSizeDegrees;
        this.failureThreshold = failureThreshold;
        this.openSeconds = openSeconds;
    }

    @PostConstruct
    public void validate() {
        if (!"fake".equals(provider) && !"osrm".equals(provider)) {
            throw new IllegalArgumentException(
                    "Unknown routing provider: '" + provider + "'. Allowed values are 'fake' or 'osrm'."
            );
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("routing.cache.max-entries must be positive, got: " + maxEntries);
        }
        if (ttlHours <= 0) {
            throw new IllegalArgumentException("routing.cache.ttl-hours must be positive, got: " + ttlHours);
        }
        if (cellSizeDegrees <= 0.0) {
            throw new IllegalArgumentException("routing.cache.cell-size-degrees must be positive, got: " + cellSizeDegrees);
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("routing.breaker.failure-threshold must be positive, got: " + failureThreshold);
        }
        if (openSeconds <= 0) {
            throw new IllegalArgumentException("routing.breaker.open-seconds must be positive, got: " + openSeconds);
        }
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock routingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public RoutingStats routingStats() {
        return new RoutingStats();
    }

    @Bean(name = "rawRoutingProvider")
    @ConditionalOnProperty(name = "routing.provider", havingValue = "fake", matchIfMissing = true)
    public RoutingProvider fakeRoutingProvider(
            @Value("${routing.fake.detour-factor:1.3}") double detourFactor) {
        return new FakeRoutingProvider(detourFactor);
    }

    @Bean(name = "rawRoutingProvider")
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

    @Bean
    public CircuitBreaker routingCircuitBreaker(
            @Value("${routing.breaker.failure-threshold:5}") int threshold,
            @Value("${routing.breaker.open-seconds:30}") long openSecs,
            Clock clock) {
        return new CircuitBreaker(threshold, Duration.ofSeconds(openSecs), clock);
    }

    @Bean
    @Primary
    public RoutingProvider routingProvider(
            @Qualifier("rawRoutingProvider") RoutingProvider rawProvider,
            CircuitBreaker circuitBreaker,
            RoutingStats stats,
            @Value("${routing.cache.enabled:true}") boolean isCacheEnabled,
            @Value("${routing.cache.max-entries:100000}") long cacheMaxEntries,
            @Value("${routing.cache.ttl-hours:24}") long cacheTtlHours,
            @Value("${routing.cache.cell-size-degrees:0.001}") double cacheCellSizeDegrees,
            @Value("${routing.cache.provider-version:v1}") String providerVersion) {

        // Chain: breaker wraps raw provider
        CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(
                rawProvider, circuitBreaker, stats
        );

        if (!isCacheEnabled) {
            return breakerProvider;
        }

        // Cache wraps breaker so cache hits avoid breaker checks entirely
        return new CachingRoutingProvider(
                breakerProvider,
                cacheMaxEntries,
                Duration.ofHours(cacheTtlHours),
                cacheCellSizeDegrees,
                providerVersion,
                stats
        );
    }
}
