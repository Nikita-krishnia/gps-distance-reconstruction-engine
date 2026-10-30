package com.example.gpsengine.security;

import com.example.gpsengine.domain.Role;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.regex.Pattern;

/**
 * Validates mandatory application claims within JWTs: tenant pattern, valid role enum,
 * and milestone-matching employeeRef for EMPLOYEE role.
 */
public class JwtClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern EMPLOYEE_REF_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null || !TENANT_ID_PATTERN.matcher(tenant.trim()).matches()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN, "Missing or invalid tenant claim", null));
        }

        String roleStr = jwt.getClaimAsString("role");
        if (roleStr == null || roleStr.isBlank()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN, "Missing role claim", null));
        }

        Role role;
        try {
            role = Role.valueOf(roleStr.trim());
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN, "Invalid role claim", null));
        }

        if (role == Role.EMPLOYEE) {
            String employeeRef = jwt.getClaimAsString("employeeRef");
            if (employeeRef == null || !EMPLOYEE_REF_PATTERN.matcher(employeeRef.trim()).matches()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN, "Missing or invalid employeeRef claim for employee", null));
            }
        }

        return OAuth2TokenValidatorResult.success();
    }
}

