package com.example.gpsengine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Test
    @DisplayName("Password encoder hashes plain text into a BCrypt hash, never storing plain text")
    void passwordIsBcryptHashed() {
        String plainPassword = "SuperSecurePassword123!";
        String hash = passwordEncoder.encode(plainPassword);

        assertThat(hash).isNotBlank();
        assertThat(hash).isNotEqualTo(plainPassword);
        assertThat(hash).startsWith("$2a$10$");
        assertThat(hash).hasSize(60);

        assertThat(passwordEncoder.matches(plainPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches("WrongPassword123!", hash)).isFalse();
    }
}

