package com.example.gpsengine.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class TripTimelineMergerTest {

    private final TripTimelineMerger merger = new TripTimelineMerger();

    @Test
    @DisplayName("Case-study scenario: 22-minute GPS gap with 2 employee drops merged into 3 consecutive segments")
    void caseStudy22MinuteGpsGapWithTwoEmployeeDrops() {
        Instant t0 = Instant.parse("2026-10-30T18:00:00Z");

        // Last GPS ping before gap (at 18:00)
        GpsPoint gpsBeforeGap = new GpsPoint(12.9200, 77.5800, t0, 10.0);

        // Gap occurs between 18:00 and 18:22 (22 minutes / 1320 seconds)
        // Two employee drops occurred in this interval:
        // Drop 1 at 18:08 (t0 + 8m)
        TripPoint drop1 = TripPoint.fromAnchor(12.9350, 77.5850, t0.plusSeconds(480), AnchorSource.EMPLOYEE_ACTUAL);
        // Drop 2 at 18:16 (t0 + 16m)
        TripPoint drop2 = TripPoint.fromAnchor(12.9500, 77.5900, t0.plusSeconds(960), AnchorSource.EMPLOYEE_ACTUAL);

        // First GPS ping after gap (at 18:22)
        GpsPoint gpsAfterGap = new GpsPoint(12.9650, 77.5950, t0.plusSeconds(1320), 10.0);

        List<GpsPoint> gpsList = List.of(gpsBeforeGap, gpsAfterGap);
        List<TripPoint> anchorList = List.of(drop1, drop2);

        List<TripPoint> merged = merger.merge(gpsList, anchorList);

        // Timeline must contain all 4 points in chronological order
        assertThat(merged).hasSize(4);
        assertThat(merged.get(0).timestamp()).isEqualTo(t0);
        assertThat(merged.get(0).origin()).isEqualTo(PointOrigin.GPS);

        assertThat(merged.get(1).timestamp()).isEqualTo(t0.plusSeconds(480));
        assertThat(merged.get(1).origin()).isEqualTo(PointOrigin.ANCHOR);
        assertThat(merged.get(1).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_ACTUAL);

        assertThat(merged.get(2).timestamp()).isEqualTo(t0.plusSeconds(960));
        assertThat(merged.get(2).origin()).isEqualTo(PointOrigin.ANCHOR);
        assertThat(merged.get(2).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_ACTUAL);

        assertThat(merged.get(3).timestamp()).isEqualTo(t0.plusSeconds(1320));
        assertThat(merged.get(3).origin()).isEqualTo(PointOrigin.GPS);

        // The former 22-minute gap is now broken down into 3 clean consecutive pairs:
        // Pair 1: gpsBeforeGap -> drop1
        // Pair 2: drop1 -> drop2
        // Pair 3: drop2 -> gpsAfterGap
        assertThat(merged.get(1).timestamp().getEpochSecond() - merged.get(0).timestamp().getEpochSecond())
                .isEqualTo(480);
        assertThat(merged.get(2).timestamp().getEpochSecond() - merged.get(1).timestamp().getEpochSecond())
                .isEqualTo(480);
        assertThat(merged.get(3).timestamp().getEpochSecond() - merged.get(2).timestamp().getEpochSecond())
                .isEqualTo(360);
    }

    @Test
    @DisplayName("Deterministic tie-breaking: on identical timestamps, GPS precedes ANCHOR, and anchorSource enum order breaks ties")
    void tieBreakOrderRules() {
        Instant tSame = Instant.parse("2026-10-30T10:00:00Z");

        GpsPoint gpsPoint = new GpsPoint(12.9716, 77.5946, tSame, 10.0);
        TripPoint anchorPlanned = TripPoint.fromAnchor(12.9716, 77.5946, tSame, AnchorSource.EMPLOYEE_PLANNED_FALLBACK);
        TripPoint anchorActual = TripPoint.fromAnchor(12.9716, 77.5946, tSame, AnchorSource.EMPLOYEE_ACTUAL);

        // Pass with anchors in reverse enum order
        List<TripPoint> merged = merger.merge(List.of(gpsPoint), List.of(anchorPlanned, anchorActual));

        assertThat(merged).hasSize(3);
        // Rule: GPS comes first
        assertThat(merged.get(0).origin()).isEqualTo(PointOrigin.GPS);
        // Rule: Between anchors on identical timestamp, EMPLOYEE_ACTUAL (ordinal 0) precedes EMPLOYEE_PLANNED_FALLBACK (ordinal 2)
        assertThat(merged.get(1).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_ACTUAL);
        assertThat(merged.get(2).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_PLANNED_FALLBACK);
    }

    @Test
    @DisplayName("Determinism: Shuffling the input points and anchors produces identical merged timelines")
    void determinismUnderShuffling() {
        Instant t0 = Instant.parse("2026-10-30T12:00:00Z");
        List<GpsPoint> gpsPoints = new ArrayList<>();
        List<TripPoint> anchors = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            gpsPoints.add(new GpsPoint(12.9 + (i * 0.01), 77.5 + (i * 0.01), t0.plusSeconds(i * 30), 10.0));
            anchors.add(TripPoint.fromAnchor(12.9 + (i * 0.01), 77.5 + (i * 0.01), t0.plusSeconds(i * 30 + 15), AnchorSource.EMPLOYEE_ACTUAL));
        }

        List<TripPoint> baseline = merger.merge(gpsPoints, anchors);

        Random random = new Random(12345);
        for (int run = 0; run < 10; run++) {
            List<GpsPoint> shuffledGps = new ArrayList<>(gpsPoints);
            List<TripPoint> shuffledAnchors = new ArrayList<>(anchors);
            Collections.shuffle(shuffledGps, random);
            Collections.shuffle(shuffledAnchors, random);

            List<TripPoint> result = merger.merge(shuffledGps, shuffledAnchors);
            assertThat(result).isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("Input lists must not be modified in-place")
    void inputListsAreUnmodified() {
        Instant t0 = Instant.parse("2026-10-30T12:00:00Z");
        List<GpsPoint> gpsPoints = new ArrayList<>(List.of(
                new GpsPoint(12.92, 77.58, t0.plusSeconds(60), 10.0),
                new GpsPoint(12.90, 77.58, t0, 10.0)
        ));
        List<GpsPoint> gpsCopy = List.copyOf(gpsPoints);

        List<TripPoint> anchors = new ArrayList<>(List.of(
                TripPoint.fromAnchor(12.95, 77.59, t0.plusSeconds(120), AnchorSource.OFFICE_PLANNED)
        ));
        List<TripPoint> anchorsCopy = List.copyOf(anchors);

        merger.merge(gpsPoints, anchors);

        assertThat(gpsPoints).isEqualTo(gpsCopy);
        assertThat(anchors).isEqualTo(anchorsCopy);
    }

    @Test
    @DisplayName("Empty or null inputs should safely return an empty unmodifiable list")
    void emptyAndNullInputsHandled() {
        assertThat(merger.merge(null, null)).isEmpty();
        assertThat(merger.merge(List.of(), null)).isEmpty();
        assertThat(merger.merge(null, List.of())).isEmpty();
        assertThat(merger.merge(List.of(), List.of())).isEmpty();
    }
}

