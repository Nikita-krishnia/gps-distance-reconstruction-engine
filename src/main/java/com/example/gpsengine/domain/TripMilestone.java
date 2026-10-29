package com.example.gpsengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "trip_milestone")
public class TripMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private MilestoneType type;

    @Column(name = "employee_ref")
    private String employeeRef;

    @Column(name = "actual_lat")
    private Double actualLat;

    @Column(name = "actual_lon")
    private Double actualLon;

    @Column(name = "actual_time")
    private Instant actualTime;

    @Column(name = "planned_lat")
    private Double plannedLat;

    @Column(name = "planned_lon")
    private Double plannedLon;

    @Column(name = "planned_time")
    private Instant plannedTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "rider_kind")
    private RiderKind riderKind;

    @Column(name = "profile_fallback_lat")
    private Double profileFallbackLat;

    @Column(name = "profile_fallback_lon")
    private Double profileFallbackLon;

    protected TripMilestone() {
        // JPA required no-arg constructor
    }

    public TripMilestone(String tenantId, Trip trip, MilestoneType type, String employeeRef,
                         Double actualLat, Double actualLon, Instant actualTime,
                         Double plannedLat, Double plannedLon, Instant plannedTime,
                         RiderKind riderKind,
                         Double profileFallbackLat, Double profileFallbackLon) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.trip = Objects.requireNonNull(trip, "trip must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.employeeRef = employeeRef;
        this.actualLat = actualLat;
        this.actualLon = actualLon;
        this.actualTime = actualTime;
        this.plannedLat = plannedLat;
        this.plannedLon = plannedLon;
        this.plannedTime = plannedTime;
        this.riderKind = riderKind;
        this.profileFallbackLat = profileFallbackLat;
        this.profileFallbackLon = profileFallbackLon;
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

    public MilestoneType getType() {
        return type;
    }

    public String getEmployeeRef() {
        return employeeRef;
    }

    public Double getActualLat() {
        return actualLat;
    }

    public Double getActualLon() {
        return actualLon;
    }

    public Instant getActualTime() {
        return actualTime;
    }

    public Double getPlannedLat() {
        return plannedLat;
    }

    public Double getPlannedLon() {
        return plannedLon;
    }

    public Instant getPlannedTime() {
        return plannedTime;
    }

    public RiderKind getRiderKind() {
        return riderKind;
    }

    public Double getProfileFallbackLat() {
        return profileFallbackLat;
    }

    public Double getProfileFallbackLon() {
        return profileFallbackLon;
    }

    @Override
    public String toString() {
        // Safe representation: never print personal employee references or coordinates
        return "TripMilestone{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", tripId=" + (trip != null ? trip.getId() : null) +
                ", type=" + type +
                ", riderKind=" + riderKind +
                '}';
    }
}

