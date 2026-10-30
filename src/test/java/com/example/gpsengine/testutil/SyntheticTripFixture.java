package com.example.gpsengine.testutil;

import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.engine.MilestoneInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared synthetic data fixtures for engine, service, and API integration tests.
 * All coordinates and employee identifiers are completely synthetic.
 */
public final class SyntheticTripFixture {

    public static final String DEFAULT_TENANT = "tenant-synth";
    public static final double DEFAULT_OFFICE_LAT = 12.9279;
    public static final double DEFAULT_OFFICE_LON = 77.6271;

    private SyntheticTripFixture() {}

    public static Trip createTrip(String tenantId) {
        return DomainFactory.createTrip(tenantId, DEFAULT_OFFICE_LAT, DEFAULT_OFFICE_LON);
    }

    public static Trip createTrip() {
        return createTrip(DEFAULT_TENANT);
    }

    public static GpsPoint gpsPoint(double lat, double lon, Instant time, Double accuracy) {
        return new GpsPoint(lat, lon, time, accuracy);
    }

    public static GpsPoint gpsPoint(double lat, double lon, Instant time) {
        return new GpsPoint(lat, lon, time, 10.0);
    }

    public static MilestoneInput tripStart(Instant time) {
        return new MilestoneInput(
                MilestoneType.TRIP_START,
                null,
                null,
                null, null, time,
                null, null, null,
                null, null, null
        );
    }

    public static MilestoneInput tripEnd(double lat, double lon, Instant time) {
        return new MilestoneInput(
                MilestoneType.TRIP_END,
                null,
                null,
                lat, lon, time,
                null, null, null,
                null, null, null
        );
    }

    public static MilestoneInput employeeDrop(String empRef, RiderKind riderKind,
                                              Double actualLat, Double actualLon, Instant actualTime,
                                              Double plannedLat, Double plannedLon, Instant plannedTime) {
        return new MilestoneInput(
                MilestoneType.DROP,
                empRef,
                riderKind,
                actualLat, actualLon, actualTime,
                plannedLat, plannedLon, plannedTime,
                null, null, null
        );
    }

    public static MilestoneInput adHocEmployeeDrop(String empRef,
                                                   Double actualLat, Double actualLon, Instant actualTime,
                                                   Double profileLat, Double profileLon, Instant profileTime) {
        return new MilestoneInput(
                MilestoneType.DROP,
                empRef,
                RiderKind.AD_HOC,
                actualLat, actualLon, actualTime,
                null, null, null,
                profileLat, profileLon, profileTime
        );
    }

    public static MilestoneInput noShow(String empRef, Double actualLat, Double actualLon, Instant actualTime,
                                        Double plannedLat, Double plannedLon, Instant plannedTime) {
        return new MilestoneInput(
                MilestoneType.NO_SHOW,
                empRef,
                RiderKind.PLANNED,
                actualLat, actualLon, actualTime,
                plannedLat, plannedLon, plannedTime,
                null, null, null
        );
    }

    public static List<GpsPing> standardPingList(String tenantId, Trip trip, Instant startTime) {
        List<GpsPing> pings = new ArrayList<>();
        pings.add(DomainFactory.createPing(tenantId, trip, 12.9716, 77.5946, startTime, 10.0));
        pings.add(DomainFactory.createPing(tenantId, trip, 12.9600, 77.6050, startTime.plusSeconds(300), 12.0));
        pings.add(DomainFactory.createPing(tenantId, trip, 12.9450, 77.6180, startTime.plusSeconds(700), 15.0));
        pings.add(DomainFactory.createPing(tenantId, trip, 12.9280, 77.6270, startTime.plusSeconds(1200), 8.0));
        return pings;
    }

    public static List<TripMilestone> standardMilestoneList(String tenantId, Trip trip, Instant startTime) {
        List<TripMilestone> milestones = new ArrayList<>();
        milestones.add(DomainFactory.createMilestone(tenantId, trip, MilestoneType.TRIP_START, null, null, null, startTime, null, null, null, null, null, null, null));
        milestones.add(DomainFactory.createMilestone(tenantId, trip, MilestoneType.DROP, "emp-001", 12.9600, 77.6050, startTime.plusSeconds(300), 12.9600, 77.6050, startTime.plusSeconds(300), RiderKind.PLANNED, null, null, null));
        milestones.add(DomainFactory.createMilestone(tenantId, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, startTime.plusSeconds(1200), null, null, null, null, null, null, null));
        return milestones;
    }
}
