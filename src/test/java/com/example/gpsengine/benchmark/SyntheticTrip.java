package com.example.gpsengine.benchmark;

import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.engine.MilestoneInput;

import java.time.Instant;
import java.util.List;

/**
 * Self-contained representation of a generated synthetic trip for benchmarking.
 */
public record SyntheticTrip(
        int tripIndex,
        double officePlannedLatitude,
        double officePlannedLongitude,
        Instant startTime,
        Instant endTime,
        List<MilestoneInput> milestones,
        List<GpsPoint> rawPings
) {
    public TripDto.CreateTripRequest toCreateTripRequest() {
        return new TripDto.CreateTripRequest(officePlannedLatitude, officePlannedLongitude);
    }

    public MilestoneDto.UploadMilestonesRequest toUploadMilestonesRequest() {
        List<MilestoneDto.MilestoneItem> items = milestones.stream()
                .map(m -> new MilestoneDto.MilestoneItem(
                        m.type(),
                        m.employeeRef(),
                        m.riderKind(),
                        m.actualLat(),
                        m.actualLon(),
                        m.actualTime(),
                        m.plannedLat(),
                        m.plannedLon(),
                        m.plannedTime(),
                        m.profileLat(),
                        m.profileLon(),
                        m.profileSignInTime()
                ))
                .toList();
        return new MilestoneDto.UploadMilestonesRequest(items);
    }

    public PingDto.UploadPingsRequest toUploadPingsRequest() {
        List<PingDto.PingItem> items = rawPings.stream()
                .map(p -> new PingDto.PingItem(p.latitude(), p.longitude(), p.timestamp(), p.accuracyMeters()))
                .toList();
        return new PingDto.UploadPingsRequest(items);
    }
}
