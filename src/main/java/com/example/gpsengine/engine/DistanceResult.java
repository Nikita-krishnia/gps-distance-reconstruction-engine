package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result record containing reconstructed trip segments, aggregated total metres,
 * system calculation status, and provider telemetry.
 */
public record DistanceResult(
        List<SegmentResult> segments,
        long totalMeters,
        TripStatus status,
        String ruleVersion,
        int providerCalls,
        Map<SegmentRule, Integer> ruleCounts
) {
    public DistanceResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");
        segments = segments != null ? List.copyOf(segments) : List.of();
        ruleCounts = (ruleCounts != null && !ruleCounts.isEmpty())
                ? Collections.unmodifiableMap(new EnumMap<>(ruleCounts))
                : Collections.emptyMap();
    }

    public static DistanceResult empty(String ruleVersion) {
        return new DistanceResult(List.of(), 0L, TripStatus.CALCULATED, ruleVersion, 0, Map.of());
    }
}
