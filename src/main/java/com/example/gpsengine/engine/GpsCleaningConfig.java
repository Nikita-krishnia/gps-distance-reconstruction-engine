package com.example.gpsengine.engine;

/**
 * Configuration thresholds for the GPS cleaning pipeline.
 *
 * @param accuracyThresholdMeters points with accuracy worse than this threshold are dropped (default: 50m)
 * @param maxSpeedKmh points implying a speed higher than this limit relative to the last accepted point are dropped (default: 120 km/h)
 */
public record GpsCleaningConfig(
        double accuracyThresholdMeters,
        double maxSpeedKmh
) {
    public static final double DEFAULT_ACCURACY_THRESHOLD_METERS = 50.0;
    public static final double DEFAULT_MAX_SPEED_KMH = 120.0;

    public static GpsCleaningConfig defaults() {
        return new GpsCleaningConfig(DEFAULT_ACCURACY_THRESHOLD_METERS, DEFAULT_MAX_SPEED_KMH);
    }
}

