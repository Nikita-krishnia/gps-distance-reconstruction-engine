package com.example.gpsengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Entity representing an application user for authentication and authorization.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,64}$");
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern EMPLOYEE_REF_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "employee_ref", length = 64)
    private String employeeRef;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
        // Required by JPA
    }

    public AppUser(String tenantId, String username, String passwordHash, Role role,
                   String employeeRef, boolean enabled, Instant createdAt) {
        validateTenantId(tenantId);
        validateUsername(username);
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (role == Role.EMPLOYEE) {
            if (employeeRef == null || employeeRef.isBlank() || !EMPLOYEE_REF_PATTERN.matcher(employeeRef).matches()) {
                throw new IllegalArgumentException("employeeRef is required and must match pattern for EMPLOYEE role");
            }
        } else {
            employeeRef = null;
        }

        this.tenantId = tenantId.trim();
        this.username = username.trim();
        this.passwordHash = passwordHash;
        this.role = role;
        this.employeeRef = employeeRef;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    private static void validateTenantId(String tenantId) {
        if (tenantId == null || !TENANT_ID_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("tenantId must be 1-64 alphanumeric characters, dashes, or underscores");
        }
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("username must be 3-64 characters matching pattern [a-zA-Z0-9._-]");
        }
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getEmployeeRef() {
        return employeeRef;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Privacy-preserving toString implementation: NEVER exposes password hash or username.
     */
    @Override
    public String toString() {
        return "AppUser{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", role=" + role +
                ", enabled=" + enabled +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppUser appUser = (AppUser) o;
        return Objects.equals(id, appUser.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

