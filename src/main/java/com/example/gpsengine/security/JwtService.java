package com.example.gpsengine.security;

import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing JWT minting and cryptographic verification using Nimbus HS256.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final String issuer;
    private final int ttlMinutes;
    private final Clock clock;

    public JwtService(JwtEncoder jwtEncoder,
                      JwtDecoder jwtDecoder,
                      @Value("${jwt.issuer:gps-distance-engine}") String issuer,
                      @Value("${jwt.ttl-minutes:15}") int ttlMinutes,
                      Optional<Clock> clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.issuer = issuer;
        this.ttlMinutes = ttlMinutes;
        this.clock = clock.orElseGet(Clock::systemUTC);
    }

    /**
     * Issues an opaque, tamper-evident HS256 JWT for the authenticated user.
     * Contains NO username, email, phone or plain personal details.
     */
    public String issueToken(AppUser user) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        String jti = UUID.randomUUID().toString();

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(exp)
                .id(jti)
                .subject(String.valueOf(user.getId()))
                .claim("tenant", user.getTenantId())
                .claim("role", user.getRole().name());

        if (user.getRole() == Role.EMPLOYEE && user.getEmployeeRef() != null) {
            builder.claim("employeeRef", user.getEmployeeRef());
        }

        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Helper to mint customized tokens for security test harnesses (e.g. testing expired tokens or missing claims).
     */
    public String issueCustomToken(String subject, String tenant, String role, String employeeRef,
                                    Instant issuedAt, Instant expiresAt, String customIssuer) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString());

        if (customIssuer != null) {
            builder.issuer(customIssuer);
        }
        if (issuedAt != null) {
            builder.issuedAt(issuedAt);
        }
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        if (subject != null) {
            builder.subject(subject);
        }
        if (tenant != null) {
            builder.claim("tenant", tenant);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        if (employeeRef != null) {
            builder.claim("employeeRef", employeeRef);
        }

        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Decodes and cryptographically verifies the token signature and claims.
     */
    public Jwt decodeToken(String token) {
        return jwtDecoder.decode(token);
    }

    public int getTtlMinutes() {
        return ttlMinutes;
    }
}

