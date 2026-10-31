package com.example.gpsengine.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.List;

/**
 * Spring Security configuration enforcing stateless JWT authentication,
 * strict role-based access control, and deny-by-default routing policies.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecretKey jwtSecretKey(@Value("${jwt.secret:${JWT_SECRET:}}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret (jwt.secret / JWT_SECRET) must be configured and cannot be blank");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT secret (jwt.secret / JWT_SECRET) must be at least 32 bytes (256 bits) for HS256");
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(secretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey,
                                 @Value("${jwt.issuer:gps-distance-engine}") String issuer,
                                 java.util.Optional<Clock> clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        clock.ifPresent(timestampValidator::setClock);

        JwtIssuerValidator issuerValidator = new JwtIssuerValidator(issuer);
        JwtClaimsValidator claimsValidator = new JwtClaimsValidator();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator, issuerValidator, claimsValidator
        ));
        return decoder;
    }

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return Collections.emptyList();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role.trim()));
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        // CSRF is disabled because this REST API is completely stateless and uses Bearer tokens
        // in HTTP Authorization headers, not session cookies. Browsers cannot automatically attach
        // Bearer tokens, eliminating CSRF vulnerabilities.
        http.csrf(AbstractHttpConfigurer::disable);

        // Stateless session management: no HTTP session is created or used
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Disable standard form login and basic auth
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);

        // Role-based authorization matrix with deny-by-default
        http.authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/hello").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()

                // ADMIN only: close invoice, actuator metrics/info, demo fault injection
                .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/close-invoice").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**", "/actuator/info").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/demo/provider-failure").hasRole("ADMIN")

                // EMPLOYEE, OPERATOR, ADMIN: read single trip details (filtered further in controller for EMPLOYEE)
                .requestMatchers(HttpMethod.GET, "/api/v1/trips/*").hasAnyRole("EMPLOYEE", "OPERATOR", "ADMIN")

                // OPERATOR and ADMIN: all other trip endpoints
                .requestMatchers("/api/v1/trips/**").hasAnyRole("OPERATOR", "ADMIN")

                // Deny all other requests by default
                .anyRequest().denyAll()
        );

        // Populate MDC with verified JWT claims (userId, tenantId) after authentication
        http.addFilterAfter(new SecurityMdcFilter(), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);

        // Configure OAuth2 Resource Server for JWT bearer authentication
        http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
        );

        // Handle exceptions outside oauth2 filter (e.g. access denied on authorization)
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
        );

        return http.build();
    }
}

