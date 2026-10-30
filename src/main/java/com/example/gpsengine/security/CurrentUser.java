package com.example.gpsengine.security;

import com.example.gpsengine.domain.Role;

/**
 * Immutable authenticated principal details resolved from verified JWT claims.
 */
public record CurrentUser(
        Long userId,
        String tenantId,
        Role role,
        String employeeRef
) {
    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}

