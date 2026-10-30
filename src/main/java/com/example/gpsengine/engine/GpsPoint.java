package com.example.gpsengine.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable GPS observation representing latitude, longitude, recording timestamp,
 * and an optional horizontal accuracy estimate in metres.
 */
public record GpsPoint(
        double latitude,
        double longitude,
        Instant timestamp,
        Double accuracyMeters
) {
    public GpsPoint {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}

