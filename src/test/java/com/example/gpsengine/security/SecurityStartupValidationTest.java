package com.example.gpsengine.security;

import com.example.gpsengine.repository.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SecurityStartupValidationTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RestAuthenticationEntryPoint.class, () -> new RestAuthenticationEntryPoint(new ObjectMapper()))
            .withBean(RestAccessDeniedHandler.class, () -> new RestAccessDeniedHandler(new ObjectMapper()));

    @Test
    @DisplayName("Startup fails when JWT secret is missing or blank")
    void missingOrBlankSecretFailsStartup() {
        baseRunner
                .withPropertyValues("jwt.secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
                    assertThat(rootCause).isInstanceOf(IllegalStateException.class);
                    assertThat(rootCause.getMessage())
                            .isEqualTo("JWT secret (jwt.secret / JWT_SECRET) must be configured and cannot be blank");
                });
    }

    @Test
    @DisplayName("Startup fails when JWT secret is under 32 bytes without logging or exposing the secret value")
    void shortSecretFailsStartupWithoutExposingSecret() {
        String shortSecret = "my-short-secret-key";
        baseRunner
                .withPropertyValues("jwt.secret=" + shortSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
                    assertThat(rootCause).isInstanceOf(IllegalStateException.class);
                    assertThat(rootCause.getMessage()).contains("must be at least 32 bytes (256 bits) for HS256");
                    assertThat(rootCause.getMessage()).doesNotContain(shortSecret);
                });
    }

    @Test
    @DisplayName("Startup fails when auth.seed.enabled=true but SEED_PASSWORD is missing or shorter than 12 characters")
    void seedingEnabledWithShortOrMissingPasswordFailsStartup() {
        String shortPassword = "shortpass";
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DatabaseUserSeeder.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestAuthenticationEntryPoint.class, () -> new RestAuthenticationEntryPoint(new ObjectMapper()))
                .withBean(RestAccessDeniedHandler.class, () -> new RestAccessDeniedHandler(new ObjectMapper()))
                .withBean(AppUserRepository.class, () -> Mockito.mock(AppUserRepository.class))
                .withPropertyValues(
                        "jwt.secret=a-very-long-secret-key-that-is-at-least-32-bytes-long!",
                        "auth.seed.enabled=true",
                        "auth.seed.password=" + shortPassword
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
                    assertThat(rootCause).isInstanceOf(IllegalStateException.class);
                    assertThat(rootCause.getMessage()).contains("at least 12 characters");
                    assertThat(rootCause.getMessage()).doesNotContain(shortPassword);
                });
    }
}

