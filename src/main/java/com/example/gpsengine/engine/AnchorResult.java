package com.example.gpsengine.engine;

import java.util.List;

/**
 * Encapsulates synthesized anchors along with auditing counters detailing which
 * fallback policies or skip rules were triggered.
 */
public record AnchorResult(
        List<TripPoint> anchors,
        int actualUsed,
        int profileFallbackUsed,
        int plannedFallbackUsed,
        int noShowAdded,
        int skippedNoValidLocation,
        boolean officeActualUsed
) {
    public AnchorResult {
        anchors = anchors != null ? List.copyOf(anchors) : List.of();
    }

    public static AnchorResult empty() {
        return new AnchorResult(List.of(), 0, 0, 0, 0, 0, false);
    }
}

