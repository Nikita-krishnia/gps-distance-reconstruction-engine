package com.example.gpsengine.security;

import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.Role;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET_STRING = "very-secure-secret-key-that-is-at-least-32-bytes-long!";
    private static final String ISSUER = "gps-distance-engine";

    private SecretKey secretKey;
    private JwtService jwtService;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        secretKey = new SecretKeySpec(SECRET_STRING.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        fixedClock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);

        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(fixedClock);
        JwtIssuerValidator issuerValidator = new JwtIssuerValidator(ISSUER);
        JwtClaimsValidator claimsValidator = new JwtClaimsValidator();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator, issuerValidator, claimsValidator
        ));

        jwtService = new JwtService(encoder, decoder, ISSUER, 15, Optional.of(fixedClock));
    }

    @Test
    @DisplayName("Valid token round-trips with correct claims and contains NO username or personal info")
    void validTokenRoundTrips() {
        AppUser user = DomainFactory.createUser(
                "tenant-alpha", "employee-user", "hashedPassword", Role.EMPLOYEE,
                "emp-001", true, fixedClock.instant()
        );

        String token = jwtService.issueToken(user);
        assertThat(token).isNotBlank();

        Jwt decoded = jwtService.decodeToken(token);

        assertThat(decoded.getSubject()).isEqualTo(String.valueOf(user.getId()));
        assertThat(decoded.getClaimAsString("tenant")).isEqualTo("tenant-alpha");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("EMPLOYEE");
        assertThat(decoded.getClaimAsString("employeeRef")).isEqualTo("emp-001");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(decoded.getIssuedAt()).isEqualTo(fixedClock.instant());
        assertThat(decoded.getExpiresAt()).isEqualTo(fixedClock.instant().plus(15, ChronoUnit.MINUTES));
        assertThat(decoded.getId()).isNotBlank();

        // Privacy guarantee: assert username and personal info are NOT present in the token
        assertThat(decoded.getClaims().containsKey("username")).isFalse();
        assertThat(decoded.getClaims().containsKey("email")).isFalse();
        assertThat(decoded.getClaims().containsKey("phone")).isFalse();
        assertThat(decoded.getClaims().containsKey("name")).isFalse();
    }

    @Test
    @DisplayName("Expired token is rejected by validator")
    void expiredTokenRejected() {
        Instant past = fixedClock.instant().minus(20, ChronoUnit.MINUTES);
        Instant expiredAt = fixedClock.instant().minus(5, ChronoUnit.MINUTES);

        String token = jwtService.issueCustomToken(
                "123", "tenant-alpha", "OPERATOR", null, past, expiredAt, ISSUER
        );

        assertThatThrownBy(() -> jwtService.decodeToken(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Tampered payload signature verification fails")
    void tamperedPayloadRejected() {
        AppUser user = DomainFactory.createUser(
                "tenant-alpha", "operator-user", "hash", Role.OPERATOR, null, true, fixedClock.instant()
        );
        String token = jwtService.issueToken(user);

        // Tamper with payload segment
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "tampered" + "." + parts[2];

        assertThatThrownBy(() -> jwtService.decodeToken(tampered))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Token signed with a different secret is rejected")
    void wrongSecretRejected() throws Exception {
        SecretKey otherKey = new SecretKeySpec(
                "completely-different-secret-key-32-bytes-long!".getBytes(StandardCharsets.UTF_8), "HmacSHA256"
        );

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .issueTime(Date.from(fixedClock.instant()))
                .expirationTime(Date.from(fixedClock.instant().plus(15, ChronoUnit.MINUTES)))
                .subject("123")
                .claim("tenant", "tenant-alpha")
                .claim("role", "ADMIN")
                .jwtID("test-id")
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(new MACSigner(otherKey.getEncoded()));
        String foreignToken = signedJWT.serialize();

        assertThatThrownBy(() -> jwtService.decodeToken(foreignToken))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Token with alg: none is strictly rejected")
    void algNoneRejected() {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .issueTime(Date.from(fixedClock.instant()))
                .expirationTime(Date.from(fixedClock.instant().plus(15, ChronoUnit.MINUTES)))
                .subject("123")
                .claim("tenant", "tenant-alpha")
                .claim("role", "ADMIN")
                .jwtID("test-none")
                .build();

        PlainJWT plainJWT = new PlainJWT(claimsSet);
        String noneToken = plainJWT.serialize();

        assertThatThrownBy(() -> jwtService.decodeToken(noneToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Token with wrong issuer is rejected")
    void wrongIssuerRejected() {
        String token = jwtService.issueCustomToken(
                "123", "tenant-alpha", "ADMIN", null,
                fixedClock.instant(), fixedClock.instant().plus(15, ChronoUnit.MINUTES), "malicious-issuer"
        );

        assertThatThrownBy(() -> jwtService.decodeToken(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Token with missing or invalid tenant claim is rejected")
    void missingOrInvalidTenantRejected() {
        String tokenNoTenant = jwtService.issueCustomToken(
                "123", null, "ADMIN", null,
                fixedClock.instant(), fixedClock.instant().plus(15, ChronoUnit.MINUTES), ISSUER
        );

        assertThatThrownBy(() -> jwtService.decodeToken(tokenNoTenant))
                .isInstanceOf(JwtValidationException.class);

        String tokenBadTenant = jwtService.issueCustomToken(
                "123", "invalid tenant spaces!", "ADMIN", null,
                fixedClock.instant(), fixedClock.instant().plus(15, ChronoUnit.MINUTES), ISSUER
        );

        assertThatThrownBy(() -> jwtService.decodeToken(tokenBadTenant))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Token with missing or invalid role claim is rejected")
    void missingOrInvalidRoleRejected() {
        String tokenNoRole = jwtService.issueCustomToken(
                "123", "tenant-alpha", null, null,
                fixedClock.instant(), fixedClock.instant().plus(15, ChronoUnit.MINUTES), ISSUER
        );

        assertThatThrownBy(() -> jwtService.decodeToken(tokenNoRole))
                .isInstanceOf(JwtValidationException.class);

        String tokenBadRole = jwtService.issueCustomToken(
                "123", "tenant-alpha", "SUPERUSER", null,
                fixedClock.instant(), fixedClock.instant().plus(15, ChronoUnit.MINUTES), ISSUER
        );

        assertThatThrownBy(() -> jwtService.decodeToken(tokenBadRole))
                .isInstanceOf(JwtValidationException.class);
    }
}

