package com.example.gpsengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    name = "distance_calculation",
    indexes = {
        @Index(name = "idx_calc_trip", columnList = "trip_id"),
        @Index(name = "idx_calc_tenant", columnList = "tenant_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_calc_trip_hash_ver_status",
            columnNames = {"trip_id", "input_hash", "rule_version", "status"}
        )
    }
)
public class DistanceCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "total_meters", nullable = false)
    private long totalMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TripStatus status;

    @Column(name = "routing_provider_calls", nullable = false)
    private int routingProviderCalls;

    @Column(name = "cache_hits", nullable = false)
    private int cacheHits;

    @Column(name = "cache_misses", nullable = false)
    private int cacheMisses;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DistanceCalculation() {
        // JPA required no-arg constructor
    }

    public DistanceCalculation(String tenantId, Trip trip, String inputHash, String ruleVersion, long totalMeters,
                               TripStatus status, int routingProviderCalls, int cacheHits,
                               int cacheMisses, Instant createdAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.trip = Objects.requireNonNull(trip, "trip must not be null");
        this.inputHash = Objects.requireNonNull(inputHash, "inputHash must not be null");
        this.ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");
        this.totalMeters = totalMeters;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.routingProviderCalls = routingProviderCalls;
        this.cacheHits = cacheHits;
        this.cacheMisses = cacheMisses;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    /**
     * Backward-compatible constructor defaulting inputHash to an empty string.
     */
    public DistanceCalculation(String tenantId, Trip trip, String ruleVersion, long totalMeters,
                               TripStatus status, int routingProviderCalls, int cacheHits,
                               int cacheMisses, Instant createdAt) {
        this(tenantId, trip, "0000000000000000000000000000000000000000000000000000000000000000",
                ruleVersion, totalMeters, status, routingProviderCalls, cacheHits, cacheMisses, createdAt);
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

    public String getInputHash() {
        return inputHash;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public long getTotalMeters() {
        return totalMeters;
    }

    public TripStatus getStatus() {
        return status;
    }

    public int getRoutingProviderCalls() {
        return routingProviderCalls;
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public int getCacheMisses() {
        return cacheMisses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "DistanceCalculation{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", tripId=" + (trip != null ? trip.getId() : null) +
                ", ruleVersion='" + ruleVersion + '\'' +
                ", totalMeters=" + totalMeters +
                ", status=" + status +
                '}';
    }
}
