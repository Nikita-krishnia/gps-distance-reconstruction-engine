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
    @DisplayName("Default configuration without properties wires FakeRoutingProvider")
    void defaultWiresFakeRoutingProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RoutingProvider.class);
            assertThat(context.getBean(RoutingProvider.class)).isInstanceOf(FakeRoutingProvider.class);
        });
    }

    @Test
    @DisplayName("Explicit routing.provider=fake wires FakeRoutingProvider with configured detour factor")
    void explicitFakeProvider() {
        runner.withPropertyValues("routing.provider=fake", "routing.fake.detour-factor=1.45")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RoutingProvider.class);
                    RoutingProvider provider = context.getBean(RoutingProvider.class);
                    assertThat(provider).isInstanceOf(FakeRoutingProvider.class);
                    FakeRoutingProvider fake = (FakeRoutingProvider) provider;
                    assertThat(fake.getDetourFactor()).isEqualTo(1.45);
                });
    }

    @Test
    @DisplayName("routing.provider=osrm wires OsrmRoutingProvider")
    void osrmProviderSelected() {
        runner.withPropertyValues(
                "routing.provider=osrm",
                "routing.osrm.base-url=http://localhost:5000",
                "routing.osrm.api-key=dummy-test-key"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RoutingProvider.class);
            assertThat(context.getBean(RoutingProvider.class)).isInstanceOf(OsrmRoutingProvider.class);
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
}

