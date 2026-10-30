package com.example.gpsengine.api.demo;

import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.OsrmRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DemoFaultInjectionControllerTest {

    @Configuration
    static class FakeRoutingTestConfig {
        @Bean(name = "rawRoutingProvider")
        public RoutingProvider rawRoutingProvider() {
            return new FakeRoutingProvider();
        }
    }

    @Configuration
    static class OsrmRoutingTestConfig {
        @Bean(name = "rawRoutingProvider")
        public RoutingProvider rawRoutingProvider() {
            return new OsrmRoutingProvider("http://localhost:5000", "", 1000, 1000, new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    @DisplayName("Demo controller is NOT created by default when property is omitted or false")
    void controllerAbsentByDefault() {
        contextRunner
                .withUserConfiguration(FakeRoutingTestConfig.class, DemoFaultInjectionController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DemoFaultInjectionController.class);
                });
    }

    @Test
    @DisplayName("Demo controller IS created when demo.fault-injection.enabled=true and routing.provider=fake")
    void controllerCreatedWhenEnabledWithFake() {
        contextRunner
                .withPropertyValues("demo.fault-injection.enabled=true", "routing.provider=fake")
                .withUserConfiguration(FakeRoutingTestConfig.class, DemoFaultInjectionController.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DemoFaultInjectionController.class);
                    DemoFaultInjectionController controller = context.getBean(DemoFaultInjectionController.class);
                    FakeRoutingProvider fake = (FakeRoutingProvider) context.getBean("rawRoutingProvider");

                    assertThat(fake.isFailing()).isFalse();
                    controller.toggleProviderFailure(new DemoFaultInjectionController.ProviderFailureRequest(true));
                    assertThat(fake.isFailing()).isTrue();
                    controller.toggleProviderFailure(new DemoFaultInjectionController.ProviderFailureRequest(false));
                    assertThat(fake.isFailing()).isFalse();
                });
    }

    @Test
    @DisplayName("Demo controller is NOT created when routing.provider=osrm even if demo switch is enabled")
    void controllerNotCreatedWhenProviderIsOsrm() {
        contextRunner
                .withPropertyValues("demo.fault-injection.enabled=true", "routing.provider=osrm")
                .withUserConfiguration(OsrmRoutingTestConfig.class, DemoFaultInjectionController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DemoFaultInjectionController.class);
                });
    }
}

