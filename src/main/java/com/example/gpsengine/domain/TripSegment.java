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
@Table(name = "trip_segment")
public class TripSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calculation_id", nullable = false)
    private DistanceCalculation calculation;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "from_lat", nullable = false)
    private Double fromLat;

    @Column(name = "from_lon", nullable = false)
    private Double fromLon;

    @Column(name = "from_time", nullable = false)
    private Instant fromTime;

    @Column(name = "to_lat", nullable = false)
    private Double toLat;

    @Column(name = "to_lon", nullable = false)
    private Double toLon;

    @Column(name = "to_time", nullable = false)
    private Instant toTime;

    @Column(name = "meters", nullable = false)
    private long meters;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule", nullable = false)
    private SegmentRule rule;

    @Column(name = "reason")
    private String reason;

    protected TripSegment() {
        // JPA required no-arg constructor
    }

    public TripSegment(String tenantId, DistanceCalculation calculation, int sequenceNumber,
                       Double fromLat, Double fromLon, Instant fromTime,
                       Double toLat, Double toLon, Instant toTime,
                       long meters, SegmentRule rule, String reason) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.calculation = Objects.requireNonNull(calculation, "calculation must not be null");
        this.sequenceNumber = sequenceNumber;
        this.fromLat = Objects.requireNonNull(fromLat, "fromLat must not be null");
        this.fromLon = Objects.requireNonNull(fromLon, "fromLon must not be null");
        this.fromTime = Objects.requireNonNull(fromTime, "fromTime must not be null");
        this.toLat = Objects.requireNonNull(toLat, "toLat must not be null");
        this.toLon = Objects.requireNonNull(toLon, "toLon must not be null");
        this.toTime = Objects.requireNonNull(toTime, "toTime must not be null");
        this.meters = meters;
        this.rule = Objects.requireNonNull(rule, "rule must not be null");
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public DistanceCalculation getCalculation() {
        return calculation;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Double getFromLat() {
        return fromLat;
    }

    public Double getFromLon() {
        return fromLon;
    }

    public Instant getFromTime() {
        return fromTime;
    }

    public Double getToLat() {
        return toLat;
    }

    public Double getToLon() {
        return toLon;
    }

    public Instant getToTime() {
        return toTime;
    }

    public long getMeters() {
        return meters;
    }

    public SegmentRule getRule() {
        return rule;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        // Safe representation: never print coordinates
        return "TripSegment{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", calculationId=" + (calculation != null ? calculation.getId() : null) +
                ", sequenceNumber=" + sequenceNumber +
                ", meters=" + meters +
                ", rule=" + rule +
                ", reason='" + reason + '\'' +
                '}';
    }
}

