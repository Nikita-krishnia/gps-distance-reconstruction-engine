package com.example.gpsengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "billing_adjustment",
    indexes = {
        @Index(name = "idx_adjustment_trip", columnList = "trip_id"),
        @Index(name = "idx_adjustment_tenant", columnList = "tenant_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_adjustment_trip_new_calc",
            columnNames = {"trip_id", "new_calculation_id"}
        )
    }
)
public class BillingAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_calculation_id")
    private DistanceCalculation oldCalculation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_calculation_id", nullable = false)
    private DistanceCalculation newCalculation;

    @Column(name = "delta_meters", nullable = false)
    private long deltaMeters;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BillingAdjustment() {
        // JPA required no-arg constructor
    }

    public BillingAdjustment(String tenantId, Trip trip, DistanceCalculation oldCalculation,
                             DistanceCalculation newCalculation, long deltaMeters, Instant createdAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.trip = Objects.requireNonNull(trip, "trip must not be null");
        this.oldCalculation = oldCalculation;
        this.newCalculation = Objects.requireNonNull(newCalculation, "newCalculation must not be null");
        this.deltaMeters = deltaMeters;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Trip getTrip() {
        return trip;
    }

    public DistanceCalculation getOldCalculation() {
        return oldCalculation;
    }

    public DistanceCalculation getNewCalculation() {
        return newCalculation;
    }

    public long getDeltaMeters() {
        return deltaMeters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "BillingAdjustment{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", tripId=" + (trip != null ? trip.getId() : null) +
                ", deltaMeters=" + deltaMeters +
                '}';
    }
}
