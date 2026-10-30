package com.example.gpsengine.engine;

/**
 * Configuration thresholds and multipliers for the segment distance engine.
 *
 * @param shortGapSeconds max seconds for a gap to be classified as short aerial (default: 70)
 * @param parkedSpeedKmh speed below which a long gap is considered parked (default: 5.0 km/h)
 * @param maxSpeedKmh maximum speed limit for plausible movement (default: 120.0 km/h)
 * @param maxRouteMeters maximum allowed road route distance before rejection (default: 100,000.0 m)
 * @param fallbackMultiplier multiplier applied to straight-line distance when routing fails (default: 1.4)
 */
public record SegmentConfig(
        int shortGapSeconds,
        double parkedSpeedKmh,
        double maxSpeedKmh,
        double maxRouteMeters,
        double fallbackMultiplier
) {
    public static final int DEFAULT_SHORT_GAP_SECONDS = 70;
    public static final double DEFAULT_PARKED_SPEED_KMH = 5.0;
    public static final double DEFAULT_MAX_SPEED_KMH = 120.0;
    public static final double DEFAULT_MAX_ROUTE_METERS = 100_000.0;
    public static final double DEFAULT_FALLBACK_MULTIPLIER = 1.4;

    public static SegmentConfig defaults() {
        return new SegmentConfig(
                DEFAULT_SHORT_GAP_SECONDS,
                DEFAULT_PARKED_SPEED_KMH,
                DEFAULT_MAX_SPEED_KMH,
                DEFAULT_MAX_ROUTE_METERS,
                DEFAULT_FALLBACK_MULTIPLIER
        );
    }
}

