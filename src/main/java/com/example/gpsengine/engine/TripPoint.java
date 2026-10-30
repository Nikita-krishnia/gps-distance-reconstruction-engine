package com.example.gpsengine.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * Unified timeline element representing either a verified GPS ping or a synthesized anchor.
 *
 * Coordinates are intentionally omitted from toString() to maintain privacy integrity.
 */
public record TripPoint(
        double latitude,
        double longitude,
        Instant timestamp,
        PointOrigin origin,
        AnchorSource anchorSource
) {
    public TripPoint {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        if (origin == PointOrigin.GPS && anchorSource != null) {
            throw new IllegalArgumentException("GPS points cannot have an anchorSource");
        }
        if (origin == PointOrigin.ANCHOR && anchorSource == null) {
            throw new IllegalArgumentException("ANCHOR points must have an anchorSource");
        }
    }

    public static TripPoint fromGps(GpsPoint gps) {
        Objects.requireNonNull(gps, "gps point must not be null");
        return new TripPoint(gps.latitude(), gps.longitude(), gps.timestamp(), PointOrigin.GPS, null);
    }

    public static TripPoint fromAnchor(double lat, double lon, Instant timestamp, AnchorSource source) {
        Objects.requireNonNull(source, "anchor source must not be null");
        return new TripPoint(lat, lon, timestamp, PointOrigin.ANCHOR, source);
    }

    @Override
    public String toString() {
        return "TripPoint{" +
                "timestamp=" + timestamp +
                ", origin=" + origin +
                ", anchorSource=" + anchorSource +
                '}';
    }
}

