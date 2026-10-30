package com.example.gpsengine.engine;

import java.util.List;

/**
 * Result record containing the filtered, sorted, clean GPS points alongside
 * deterministic diagnostic counts of points dropped at each pipeline stage.
 */
public record CleaningResult(
        List<GpsPoint> cleanedPoints,
        int invalidCoordinates,
        int duplicates,
        int lowAccuracy,
        int teleports
) {
    public CleaningResult {
        cleanedPoints = cleanedPoints != null ? List.copyOf(cleanedPoints) : List.of();
    }

    public static CleaningResult empty() {
        return new CleaningResult(List.of(), 0, 0, 0, 0);
    }
}

