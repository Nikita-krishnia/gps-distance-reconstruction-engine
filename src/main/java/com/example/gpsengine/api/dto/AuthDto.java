package com.example.gpsengine.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDto {

    public record LoginRequest(
            @NotBlank(message = "username must not be blank")
            @Size(max = 128, message = "username cannot exceed 128 characters")
            String username,

            @NotBlank(message = "password must not be blank")
            @Size(max = 128, message = "password cannot exceed 128 characters")
            String password
    ) {}

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds
    ) {}
}

