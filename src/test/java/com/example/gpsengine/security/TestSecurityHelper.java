package com.example.gpsengine.security;

import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Test helper for integration and slice tests to seed users and mint valid JWTs.
 */
@Component
public class TestSecurityHelper {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public TestSecurityHelper(AppUserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AppUser createOrGetUser(String username, String tenantId, Role role, String employeeRef) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            AppUser user = DomainFactory.createUser(
                    tenantId,
                    username,
                    passwordEncoder.encode("secret-password-123"),
                    role,
                    employeeRef,
                    true,
                    Instant.now()
            );
            return userRepository.save(user);
        });
    }

    public String generateToken(String username, String tenantId, Role role, String employeeRef) {
        AppUser user = createOrGetUser(username, tenantId, role, employeeRef);
        return jwtService.issueToken(user);
    }

    public String generateToken(AppUser user) {
        return jwtService.issueToken(user);
    }
}

