package com.example.gpsengine.security;

import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.repository.AppUserRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Seeds default administrative and operational users for development/testing
 * when {@code auth.seed.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "auth.seed.enabled", havingValue = "true")
public class DatabaseUserSeeder implements InitializingBean {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String seedPassword;

    public DatabaseUserSeeder(AppUserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              Optional<Clock> clock,
                              @Value("${auth.seed.password:${SEED_PASSWORD:}}") String seedPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock.orElseGet(Clock::systemUTC);
        this.seedPassword = seedPassword;
    }

    @Override
    public void afterPropertiesSet() {
        if (seedPassword == null || seedPassword.isBlank() || seedPassword.length() < 12) {
            throw new IllegalStateException(
                    "SEED_PASSWORD environment variable is required and must be at least 12 characters when auth.seed.enabled is true"
            );
        }
        seedUsers();
    }

    private void seedUsers() {
        Instant now = clock.instant();
        String passwordHash = passwordEncoder.encode(seedPassword);

        seedIfMissing("admin-a", "tenant-a", Role.ADMIN, null, passwordHash, now);
        seedIfMissing("operator-a", "tenant-a", Role.OPERATOR, null, passwordHash, now);
        seedIfMissing("employee-a", "tenant-a", Role.EMPLOYEE, "emp-001", passwordHash, now);
        seedIfMissing("operator-b", "tenant-b", Role.OPERATOR, null, passwordHash, now);
    }

    private void seedIfMissing(String username, String tenantId, Role role,
                               String employeeRef, String passwordHash, Instant now) {
        if (userRepository.findByUsername(username).isEmpty()) {
            AppUser user = DomainFactory.createUser(
                    tenantId, username, passwordHash, role, employeeRef, true, now
            );
            userRepository.save(user);
        }
    }
}

