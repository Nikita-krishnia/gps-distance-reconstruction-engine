package com.example.gpsengine.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Factory helper for clean and safe domain entity instantiation matching
 * existing database constructors without modifying entity definitions.
 */
public final class DomainFactory {

    private DomainFactory() {}

    public static Trip createTrip(String tenantId, Double officeLat, Double officeLon) {
        return new Trip(
                tenantId,
                TripStatus.NEW,
                BillingStatus.OPEN,
                officeLat,
                officeLon,
                Instant.now()
        );
    }

    public static GpsPing createPing(String tenantId, Trip trip, Double lat, Double lon, Instant recordedAt, Double accuracy) {
        return new GpsPing(tenantId, trip, lat, lon, recordedAt, accuracy);
    }

    public static TripMilestone createMilestone(
            String tenantId, Trip trip, MilestoneType type, String employeeRef,
            Double actualLat, Double actualLon, Instant actualTime,
            Double plannedLat, Double plannedLon, Instant plannedTime,
            RiderKind riderKind,
            Double profileFallbackLat, Double profileFallbackLon, Instant profileSignInTime) {
        return new TripMilestone(
                tenantId, trip, type, employeeRef,
                actualLat, actualLon, actualTime,
                plannedLat, plannedLon, plannedTime,
                riderKind, profileFallbackLat, profileFallbackLon, profileSignInTime
        );
    }

    public static DistanceCalculation createCalculation(
            String tenantId, Trip trip, String inputHash, String ruleVersion, long totalMeters,
            TripStatus status, int routingProviderCalls, int cacheHits, int cacheMisses) {
        return new DistanceCalculation(
                tenantId, trip, inputHash, ruleVersion, totalMeters, status,
                routingProviderCalls, cacheHits, cacheMisses, Instant.now()
        );
    }

    public static TripSegment createSegment(
            String tenantId, DistanceCalculation calculation, int sequenceNumber,
            Double fromLat, Double fromLon, Instant fromTime,
            Double toLat, Double toLon, Instant toTime,
            long meters, SegmentRule rule, String reason) {
        return new TripSegment(
                tenantId, calculation, sequenceNumber,
                fromLat, fromLon, fromTime,
                toLat, toLon, toTime,
                meters, rule, reason
        );
    }

    public static BillingAdjustment createAdjustment(
            String tenantId, Trip trip, DistanceCalculation oldCalc,
            DistanceCalculation newCalc, long deltaMeters) {
        return new BillingAdjustment(
                tenantId, trip, oldCalc, newCalc, deltaMeters, Instant.now()
        );
    }
}
