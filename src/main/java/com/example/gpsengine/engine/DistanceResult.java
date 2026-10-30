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
 * system calculation status, and provider / cache telemetry.
 *
 * @param segments chronological list of classified trip segments
 * @param totalMeters sum of rounded whole-meter segment distances
 * @param status CALCULATED if all segments resolved, or DEGRADED if fallback aerial was triggered
 * @param ruleVersion identifier of the applied reconstruction business rule set
 * @param providerCalls count of attempted upstream routing provider invocations
 * @param cacheHits count of segments resolved directly from routing cache
 * @param cacheMisses count of segments requiring fresh upstream routing provider computation
 * @param ruleCounts breakdown of segment rules applied across the trip
 */
public record DistanceResult(
        List<SegmentResult> segments,
        long totalMeters,
        TripStatus status,
        String ruleVersion,
        int providerCalls,
        int cacheHits,
        int cacheMisses,
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

    /**
     * Backward-compatible convenience constructor defaulting cacheHits and cacheMisses to 0.
     */
    public DistanceResult(
            List<SegmentResult> segments,
            long totalMeters,
            TripStatus status,
            String ruleVersion,
            int providerCalls,
            Map<SegmentRule, Integer> ruleCounts
    ) {
        this(segments, totalMeters, status, ruleVersion, providerCalls, 0, 0, ruleCounts);
    }

    public static DistanceResult empty(String ruleVersion) {
        return new DistanceResult(List.of(), 0L, TripStatus.CALCULATED, ruleVersion, 0, 0, 0, Map.of());
    }
}
