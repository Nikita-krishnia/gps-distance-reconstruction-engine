package com.example.gpsengine.routing;

import com.example.gpsengine.config.RoutingConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RoutingConfiguration.class);

    @Test
    @DisplayName("Default configuration: primary bean is CachingRoutingProvider, raw bean is FakeRoutingProvider")
    void defaultWiresCachingAndFake() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // Raw bean verification
            assertThat(context).hasBean("rawRoutingProvider");
            RoutingProvider raw = (RoutingProvider) context.getBean("rawRoutingProvider");
            assertThat(raw).isInstanceOf(FakeRoutingProvider.class);

            // Primary bean verification
            RoutingProvider primary = context.getBean(RoutingProvider.class);
            assertThat(primary).isInstanceOf(CachingRoutingProvider.class);

            // Circuit breaker and telemetry beans
            assertThat(context).hasSingleBean(CircuitBreaker.class);
            assertThat(context).hasSingleBean(RoutingStats.class);
        });
    }

    @Test
    @DisplayName("Explicit routing.provider=fake wires FakeRoutingProvider as raw bean with configured detour")
    void explicitFakeProvider() {
        runner.withPropertyValues("routing.provider=fake", "routing.fake.detour-factor=1.45")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoutingProvider raw = (RoutingProvider) context.getBean("rawRoutingProvider");
                    assertThat(raw).isInstanceOf(FakeRoutingProvider.class);
                    FakeRoutingProvider fake = (FakeRoutingProvider) raw;
                    assertThat(fake.getDetourFactor()).isEqualTo(1.45);

                    RoutingProvider primary = context.getBean(RoutingProvider.class);
                    assertThat(primary).isInstanceOf(CachingRoutingProvider.class);
                });
    }

    @Test
    @DisplayName("routing.provider=osrm wires OsrmRoutingProvider as raw bean and caching decorator as primary")
    void osrmProviderSelected() {
        runner.withPropertyValues(
                "routing.provider=osrm",
                "routing.osrm.base-url=http://localhost:5000",
                "routing.osrm.api-key=dummy-test-key"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            RoutingProvider raw = (RoutingProvider) context.getBean("rawRoutingProvider");
            assertThat(raw).isInstanceOf(OsrmRoutingProvider.class);

            RoutingProvider primary = context.getBean(RoutingProvider.class);
            assertThat(primary).isInstanceOf(CachingRoutingProvider.class);
        });
    }

    @Test
    @DisplayName("routing.cache.enabled=false skips cache: primary bean is CircuitBreakerRoutingProvider")
    void cacheDisabledSkipsCachingProvider() {
        runner.withPropertyValues("routing.cache.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoutingProvider primary = context.getBean(RoutingProvider.class);
                    assertThat(primary).isInstanceOf(CircuitBreakerRoutingProvider.class);
                    CircuitBreakerRoutingProvider breakerProvider = (CircuitBreakerRoutingProvider) primary;
                    assertThat(breakerProvider.getDelegate()).isInstanceOf(FakeRoutingProvider.class);
                });
    }

    @Test
    @DisplayName("Unknown routing provider value fails fast with descriptive message at startup")
    void unknownProviderFailsStartup() {
        runner.withPropertyValues("routing.provider=something-else")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Unknown routing provider: 'something-else'. Allowed values are 'fake' or 'osrm'.");
                });
    }

    @Test
    @DisplayName("Invalid configuration values (negative/zero entries, TTL, thresholds) fail startup fast")
    void invalidConfigurationPropertiesFailStartup() {
        // Negative max entries
        runner.withPropertyValues("routing.cache.max-entries=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("routing.cache.max-entries must be positive, got: 0");
                });

        // Negative TTL
        runner.withPropertyValues("routing.cache.ttl-hours=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("routing.cache.ttl-hours must be positive, got: -1");
                });

        // Negative cell size
        runner.withPropertyValues("routing.cache.cell-size-degrees=0.0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("routing.cache.cell-size-degrees must be positive, got: 0.0");
                });

        // Negative breaker threshold
        runner.withPropertyValues("routing.breaker.failure-threshold=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("routing.breaker.failure-threshold must be positive, got: 0");
                });
    }
}
