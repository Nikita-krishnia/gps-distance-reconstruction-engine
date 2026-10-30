package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;

import java.util.Objects;

/**
 * Immutable reconstruction of an individual segment between consecutive points on the timeline.
 *
 * Coordinates are intentionally omitted from toString() for data privacy.
 */
public record SegmentResult(
        int sequenceNumber,
        TripPoint from,
        TripPoint to,
        long meters,
        SegmentRule rule,
        String reason
) {
    public SegmentResult {
        Objects.requireNonNull(from, "from point must not be null");
        Objects.requireNonNull(to, "to point must not be null");
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be empty or blank");
        }
    }

    @Override
    public String toString() {
        return "SegmentResult{" +
                "sequenceNumber=" + sequenceNumber +
                ", meters=" + meters +
                ", rule=" + rule +
                ", reason='" + reason + '\'' +
                '}';
    }
}

