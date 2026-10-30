package com.example.gpsengine.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic GPS data cleaning engine that filters out noisy, invalid, duplicate,
 * low-accuracy, and physically impossible teleport pings.
 *
 * PIPELINE ORDER (by intentional design):
 * 1. Invalid coordinates: Eliminate malformed fixes first so downstream geometry and haversine calculations are safe.
 * 2. Deterministic sort: Establish canonical chronological order with deterministic tie-breaking (accuracy asc, lat, lon).
 * 3. Deduplication: Merge redundant pings with identical timestamps and 6-decimal coordinates, keeping the most accurate fix.
 * 4. Low accuracy: Discard fixes whose horizontal uncertainty exceeds the allowed tolerance threshold.
 * 5. Teleport rejection: Discard points that imply a speed greater than maxSpeedKmh relative to the LAST ACCEPTED point.
 *
 * Time Complexity: O(n log n) due to sorting, where n is the number of raw pings.
 * Space Complexity: O(n) auxiliary space for filtered copies and duplicate tracking.
 */
public class GpsCleaner {

    private record DuplicateKey(Instant timestamp, long latE6, long lonE6) {}

    /**
     * Cleans raw GPS points according to the deterministic 5-step pipeline.
     *
     * @param raw the input list of raw GPS points (remains unmutated)
     * @param config the cleaning configuration containing accuracy and speed thresholds
     * @return a CleaningResult with the clean points and dropped-point metrics
     */
    public CleaningResult clean(List<GpsPoint> raw, GpsCleaningConfig config) {
        if (raw == null || raw.isEmpty()) {
            return CleaningResult.empty();
        }

        GpsCleaningConfig activeConfig = config != null ? config : GpsCleaningConfig.defaults();

        int invalidCoordinates = 0;
        int duplicates = 0;
        int lowAccuracy = 0;
        int teleports = 0;

        // Step 1: Drop points with invalid coordinates
        List<GpsPoint> validCoordinates = new ArrayList<>(raw.size());
        for (GpsPoint point : raw) {
            if (point == null || !isValidCoordinate(point.latitude(), point.longitude())) {
                invalidCoordinates++;
            } else {
                validCoordinates.add(point);
            }
        }

        if (validCoordinates.isEmpty()) {
            return new CleaningResult(List.of(), invalidCoordinates, 0, 0, 0);
        }

        // Step 2: Deterministic sort by timestamp, tie-broken by accuracy (nulls last), latitude, and longitude
        Comparator<GpsPoint> comparator = Comparator
                .comparing(GpsPoint::timestamp)
                .thenComparing(GpsPoint::accuracyMeters, Comparator.nullsLast(Double::compareTo))
                .thenComparingDouble(GpsPoint::latitude)
                .thenComparingDouble(GpsPoint::longitude);
        validCoordinates.sort(comparator);

        // Step 3: Remove duplicates (same timestamp and same coordinates rounded to 6 decimals)
        // Since the list was sorted with most accurate first, the first entry encountered is retained.
        List<GpsPoint> deduplicated = new ArrayList<>(validCoordinates.size());
        Set<DuplicateKey> seenKeys = new HashSet<>(validCoordinates.size());
        for (GpsPoint point : validCoordinates) {
            long latE6 = Math.round(point.latitude() * 1_000_000.0);
            long lonE6 = Math.round(point.longitude() * 1_000_000.0);
            DuplicateKey key = new DuplicateKey(point.timestamp(), latE6, lonE6);

            if (seenKeys.add(key)) {
                deduplicated.add(point);
            } else {
                duplicates++;
            }
        }

        // Step 4: Drop low-accuracy points
        // Accuracy > threshold is dropped; == threshold is kept; null accuracy is kept.
        List<GpsPoint> highAccuracy = new ArrayList<>(deduplicated.size());
        for (GpsPoint point : deduplicated) {
            if (point.accuracyMeters() != null && point.accuracyMeters() > activeConfig.accuracyThresholdMeters()) {
                lowAccuracy++;
            } else {
                highAccuracy.add(point);
            }
        }

        if (highAccuracy.isEmpty()) {
            return new CleaningResult(List.of(), invalidCoordinates, duplicates, lowAccuracy, 0);
        }

        // Step 5: Drop teleports by comparing implied speed against the LAST ACCEPTED point
        List<GpsPoint> cleaned = new ArrayList<>(highAccuracy.size());
        GpsPoint lastAccepted = null;

        for (GpsPoint point : highAccuracy) {
            if (lastAccepted == null) {
                lastAccepted = point;
                cleaned.add(point);
                continue;
            }

            long deltaMillis = Duration.between(lastAccepted.timestamp(), point.timestamp()).toMillis();
            double distanceMeters = HaversineCalculator.distanceMeters(lastAccepted, point);

            if (deltaMillis <= 0) {
                if (distanceMeters > 0.0) {
                    // Infinite implied speed: position jumped with zero time elapsed
                    teleports++;
                    continue;
                } else {
                    // Zero time and zero distance
                    cleaned.add(point);
                    lastAccepted = point;
                    continue;
                }
            }

            // Convert milliseconds to hours: hours = deltaMillis / (1000 * 3600)
            // Convert metres to kilometres: km = distanceMeters / 1000.0
            // Implied speed in km/h = km / hours
            double timeHours = (double) deltaMillis / 3_600_000.0;
            double distanceKm = distanceMeters / 1000.0;
            double impliedSpeedKmh = distanceKm / timeHours;

            // Strict comparison with unrounded double speed
            if (impliedSpeedKmh > activeConfig.maxSpeedKmh()) {
                teleports++;
            } else {
                lastAccepted = point;
                cleaned.add(point);
            }
        }

        return new CleaningResult(cleaned, invalidCoordinates, duplicates, lowAccuracy, teleports);
    }

    private static boolean isValidCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }
}

