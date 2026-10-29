package com.example.gpsengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "trip",
    indexes = {
        @Index(name = "idx_trip_tenant", columnList = "tenant_id")
    }
)
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TripStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false)
    private BillingStatus billingStatus;

    @Column(name = "office_planned_lat")
    private Double officePlannedLat;

    @Column(name = "office_planned_lon")
    private Double officePlannedLon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Trip() {
        // JPA required no-arg constructor
    }

    public Trip(String tenantId, TripStatus status, BillingStatus billingStatus,
                Double officePlannedLat, Double officePlannedLon, Instant createdAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.billingStatus = Objects.requireNonNull(billingStatus, "billingStatus must not be null");
        this.officePlannedLat = officePlannedLat;
        this.officePlannedLon = officePlannedLon;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
    }

    public BillingStatus getBillingStatus() {
        return billingStatus;
    }

    public void setBillingStatus(BillingStatus billingStatus) {
        this.billingStatus = billingStatus;
    }

    public Double getOfficePlannedLat() {
        return officePlannedLat;
    }

    public Double getOfficePlannedLon() {
        return officePlannedLon;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        // Safe representation: never print coordinates
        return "Trip{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", status=" + status +
                ", billingStatus=" + billingStatus +
                ", createdAt=" + createdAt +
                '}';
    }
}

