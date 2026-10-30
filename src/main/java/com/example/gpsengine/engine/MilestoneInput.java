package com.example.gpsengine.engine;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;

import java.time.Instant;
import java.util.Objects;

/**
 * Plain-Java transfer model representing raw milestone inputs for anchor synthesis.
 *
 * Coordinates and opaque employee references are omitted from toString() for data privacy.
 */
public record MilestoneInput(
        MilestoneType type,
        String employeeRef,
        RiderKind riderKind,
        Double actualLat,
        Double actualLon,
        Instant actualTime,
        Double plannedLat,
        Double plannedLon,
        Instant plannedTime,
        Double profileLat,
        Double profileLon,
        Instant profileSignInTime
) {
    public MilestoneInput {
        Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Determines a representative timestamp used for deterministic sorting prior to processing.
     */
    public Instant sortTime() {
        if (actualTime != null) {
            return actualTime;
        }
        if (plannedTime != null) {
            return plannedTime;
        }
        return profileSignInTime;
    }

    @Override
    public String toString() {
        return "MilestoneInput{" +
                "type=" + type +
                ", riderKind=" + riderKind +
                '}';
    }
}

