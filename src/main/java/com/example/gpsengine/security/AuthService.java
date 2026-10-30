package com.example.gpsengine.security;

import com.example.gpsengine.api.dto.AuthDto;
import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.error.UnauthenticatedException;
import com.example.gpsengine.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service managing user authentication, constant-time password matching, and JWT issuance.
 */
@Service
public class AuthService {

    /**
     * Fixed, syntactically valid BCrypt hash used for constant-time comparisons when a user
     * is not found, mitigating username enumeration and timing side-channel attacks.
     */
    private static final String DUMMY_BCRYPT_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiMtiUoOaVnviq/Lw0Q7uWd0u7vG3rG";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        String username = request.username() != null ? request.username().trim() : "";
        String password = request.password() != null ? request.password() : "";

        Optional<AppUser> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            // Constant-time execution: perform BCrypt matching against dummy hash so latency
            // does not disclose whether the requested username exists in the database.
            passwordEncoder.matches(password, DUMMY_BCRYPT_HASH);
            throw new UnauthenticatedException("Invalid credentials");
        }

        AppUser user = userOpt.get();
        boolean passwordMatches = passwordEncoder.matches(password, user.getPasswordHash());

        // Identical failure response for bad password, unknown user, and disabled user
        if (!passwordMatches || !user.isEnabled()) {
            throw new UnauthenticatedException("Invalid credentials");
        }

        String token = jwtService.issueToken(user);
        long expiresInSeconds = jwtService.getTtlMinutes() * 60L;

        return new AuthDto.LoginResponse(token, "Bearer", expiresInSeconds);
    }
}

