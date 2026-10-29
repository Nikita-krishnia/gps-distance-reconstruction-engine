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

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "gps_ping",
    indexes = {
        @Index(name = "idx_gps_ping_trip_recorded", columnList = "trip_id, recorded_at")
    }
)
public class GpsPing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lon", nullable = false)
    private Double lon;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    protected GpsPing() {
        // JPA required no-arg constructor
    }

    public GpsPing(String tenantId, Trip trip, Double lat, Double lon, Instant recordedAt, Double accuracyMeters) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.trip = Objects.requireNonNull(trip, "trip must not be null");
        this.lat = Objects.requireNonNull(lat, "lat must not be null");
        this.lon = Objects.requireNonNull(lon, "lon must not be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        this.accuracyMeters = accuracyMeters;
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

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Double getAccuracyMeters() {
        return accuracyMeters;
    }

    @Override
    public String toString() {
        // Safe representation: never print coordinates
        return "GpsPing{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", tripId=" + (trip != null ? trip.getId() : null) +
                ", recordedAt=" + recordedAt +
                ", accuracyMeters=" + accuracyMeters +
                '}';
    }
}

