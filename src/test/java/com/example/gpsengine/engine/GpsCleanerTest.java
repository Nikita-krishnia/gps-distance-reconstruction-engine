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
import static org.assertj.core.api.Assertions.within;

@Tag("unit")
class GpsCleanerTest {

    private final GpsCleaner cleaner = new GpsCleaner();
    private final GpsCleaningConfig defaultConfig = GpsCleaningConfig.defaults();

    @Test
    @DisplayName("Unsorted input pings should come out strictly sorted by timestamp")
    void unsortedInputComesOutSorted() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint p1 = new GpsPoint(12.9710, 77.5940, t0.plusSeconds(30), 10.0);
        GpsPoint p2 = new GpsPoint(12.9712, 77.5942, t0.plusSeconds(10), 10.0);
        GpsPoint p3 = new GpsPoint(12.9714, 77.5944, t0.plusSeconds(20), 10.0);

        CleaningResult result = cleaner.clean(List.of(p1, p2, p3), defaultConfig);

        assertThat(result.cleanedPoints()).containsExactly(p2, p3, p1);
        assertThat(result.cleanedPoints()).isSortedAccordingTo((a, b) -> a.timestamp().compareTo(b.timestamp()));
    }

    @Test
    @DisplayName("Duplicates: exact duplicates removed, distinct coords at same time is not a duplicate, more accurate retained")
    void duplicateHandlingRules() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");

        // Two points with identical timestamp and coordinates (rounded to 6 decimals), different accuracy
        GpsPoint accuratePing = new GpsPoint(12.9715001, 77.5940001, t0, 5.0);
        GpsPoint coarsePing = new GpsPoint(12.9715004, 77.5940004, t0, 35.0);

        // A distinct location recorded at the same timestamp (different coords -> NOT counted as duplicate)
        GpsPoint differentLocationSameTime = new GpsPoint(12.972500, 77.595000, t0, 10.0);

        // A valid point later in time
        GpsPoint validLater = new GpsPoint(12.971700, 77.594200, t0.plusSeconds(10), 10.0);

        CleaningResult result = cleaner.clean(
                List.of(coarsePing, accuratePing, differentLocationSameTime, validLater),
                defaultConfig
        );

        // Exactly 1 duplicate dropped (coarsePing, since accuratePing was kept due to lower accuracy error)
        assertThat(result.duplicates()).isEqualTo(1);
        // differentLocationSameTime is NOT a duplicate; it gets dropped in step 5 as zero-time teleport
        assertThat(result.teleports()).isEqualTo(1);
        // Cleaned list contains the accurate ping and the subsequent valid point
        assertThat(result.cleanedPoints()).containsExactly(accuratePing, validLater);
    }

    @Test
    @DisplayName("Accuracy: worse than threshold dropped, equal to threshold kept, null kept")
    void accuracyThresholdRules() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint goodPing = new GpsPoint(12.9710, 77.5940, t0, 20.0);
        GpsPoint boundaryPing = new GpsPoint(12.9711, 77.5941, t0.plusSeconds(10), 50.0); // exactly threshold
        GpsPoint badPing = new GpsPoint(12.9712, 77.5942, t0.plusSeconds(20), 50.1); // worse than threshold
        GpsPoint nullAccuracyPing = new GpsPoint(12.9713, 77.5943, t0.plusSeconds(30), null); // missing accuracy kept

        CleaningResult result = cleaner.clean(List.of(goodPing, boundaryPing, badPing, nullAccuracyPing), defaultConfig);

        assertThat(result.lowAccuracy()).isEqualTo(1);
        assertThat(result.cleanedPoints()).containsExactly(goodPing, boundaryPing, nullAccuracyPing);
    }

    @Test
    @DisplayName("Invalid coordinates outside [-90, 90] lat or [-180, 180] lon should be dropped")
    void invalidCoordinatesDropped() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint valid = new GpsPoint(12.9710, 77.5940, t0, 10.0);
        GpsPoint invalidLatHigh = new GpsPoint(95.0, 77.5940, t0.plusSeconds(10), 10.0);
        GpsPoint invalidLatLow = new GpsPoint(-91.0, 77.5940, t0.plusSeconds(20), 10.0);
        GpsPoint invalidLonHigh = new GpsPoint(12.9710, 181.0, t0.plusSeconds(30), 10.0);
        GpsPoint invalidLonLow = new GpsPoint(12.9710, -185.0, t0.plusSeconds(40), 10.0);

        CleaningResult result = cleaner.clean(
                List.of(valid, invalidLatHigh, invalidLatLow, invalidLonHigh, invalidLonLow),
                defaultConfig
        );

        assertThat(result.invalidCoordinates()).isEqualTo(4);
        assertThat(result.cleanedPoints()).containsExactly(valid);
    }

    @Test
    @DisplayName("Teleport: burst of pings ~9 km away over 4s dropped, subsequent good point accepted against last accepted")
    void teleportBurstDroppedAndRecoversAgainstLastAccepted() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");

        // Legitimate movement: Start point A
        GpsPoint pointA = new GpsPoint(12.9716, 77.5946, t0, 10.0);

        // Teleport anomaly: Jump ~9 km north (~0.081 degrees lat) over 4 seconds
        // Distance ~ 9,000 metres in 4 seconds = 2,250 m/s = 8,100 km/h (impossible)
        GpsPoint burst1 = new GpsPoint(13.0526, 77.5946, t0.plusSeconds(1), 10.0);
        GpsPoint burst2 = new GpsPoint(13.0528, 77.5946, t0.plusSeconds(2), 10.0);
        GpsPoint burst3 = new GpsPoint(13.0530, 77.5946, t0.plusSeconds(3), 10.0);
        GpsPoint burst4 = new GpsPoint(13.0532, 77.5946, t0.plusSeconds(4), 10.0);

        // Legitimate point B: 30 seconds after Point A, 500m ahead along normal route
        // 500m in 30 seconds = 16.6 m/s = 60 km/h (perfectly plausible cab speed)
        GpsPoint pointB = new GpsPoint(12.9761, 77.5946, t0.plusSeconds(30), 10.0);

        CleaningResult result = cleaner.clean(
                List.of(pointA, burst1, burst2, burst3, burst4, pointB),
                defaultConfig
        );

        assertThat(result.teleports()).isEqualTo(4);
        assertThat(result.cleanedPoints()).containsExactly(pointA, pointB);

        // Total path distance of cleaned points equals the direct distance between A and B
        double expectedPathDistance = HaversineCalculator.distanceMeters(pointA, pointB);
        double actualCleanedDistance = HaversineCalculator.distanceMeters(
                result.cleanedPoints().get(0),
                result.cleanedPoints().get(1)
        );
        assertThat(actualCleanedDistance).isCloseTo(expectedPathDistance, within(0.001));
    }

    @Test
    @DisplayName("Teleport boundary: speed exactly at limit (120 km/h) is kept, just above is dropped")
    void teleportSpeedBoundaryConditions() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint start = new GpsPoint(0.0, 0.0, t0, 10.0);

        // In 30 seconds, 120 km/h covers: (120 * 1000 / 3600) * 30 = 1000.0 metres
        // 1 metre in latitude along equator = 1 / 111194.9266 degrees ~= 8.993216e-6
        // Let's use Haversine to compute coordinates for exactly 1000m and 1001m
        double latBoundary = (1000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        double latAbove = (1001.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);

        GpsPoint boundaryPoint = new GpsPoint(latBoundary, 0.0, t0.plusSeconds(30), 10.0);
        CleaningResult boundaryResult = cleaner.clean(List.of(start, boundaryPoint), defaultConfig);
        assertThat(boundaryResult.teleports()).isEqualTo(0);
        assertThat(boundaryResult.cleanedPoints()).hasSize(2);

        GpsPoint aboveLimitPoint = new GpsPoint(latAbove, 0.0, t0.plusSeconds(30), 10.0);
        CleaningResult aboveResult = cleaner.clean(List.of(start, aboveLimitPoint), defaultConfig);
        assertThat(aboveResult.teleports()).isEqualTo(1);
        assertThat(aboveResult.cleanedPoints()).containsExactly(start);
    }

    @Test
    @DisplayName("Zero time difference with different coordinates implies infinite speed and should be dropped as teleport")
    void zeroTimeDifferenceWithDifferentCoordinatesDropped() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint p1 = new GpsPoint(12.9710, 77.5940, t0, 10.0);
        // Different coordinate at the exact same timestamp
        GpsPoint p2 = new GpsPoint(12.9810, 77.5940, t0, 10.0);

        CleaningResult result = cleaner.clean(List.of(p1, p2), defaultConfig);

        assertThat(result.teleports()).isEqualTo(1);
        assertThat(result.cleanedPoints()).containsExactly(p1);
    }

    @Test
    @DisplayName("Empty or null input list should safely return an empty result without throwing exceptions")
    void emptyAndNullInputHandledSafely() {
        CleaningResult nullResult = cleaner.clean(null, defaultConfig);
        assertThat(nullResult.cleanedPoints()).isEmpty();
        assertThat(nullResult.invalidCoordinates()).isEqualTo(0);

        CleaningResult emptyResult = cleaner.clean(List.of(), defaultConfig);
        assertThat(emptyResult.cleanedPoints()).isEmpty();
        assertThat(emptyResult.invalidCoordinates()).isEqualTo(0);
    }

    @Test
    @DisplayName("Determinism: Shuffling raw input pings repeatedly must always yield identical clean output")
    void determinismUnderShuffling() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        List<GpsPoint> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            points.add(new GpsPoint(12.9700 + (i * 0.001), 77.5900 + (i * 0.001), t0.plusSeconds(i * 10), 15.0));
        }
        // Add a duplicate and a teleport
        points.add(new GpsPoint(12.9700, 77.5900, t0, 30.0));
        points.add(new GpsPoint(13.9700, 77.5900, t0.plusSeconds(5), 10.0));

        CleaningResult baseline = cleaner.clean(points, defaultConfig);

        Random random = new Random(42);
        for (int run = 0; run < 10; run++) {
            List<GpsPoint> shuffled = new ArrayList<>(points);
            Collections.shuffle(shuffled, random);
            CleaningResult shuffledResult = cleaner.clean(shuffled, defaultConfig);

            assertThat(shuffledResult.cleanedPoints()).isEqualTo(baseline.cleanedPoints());
            assertThat(shuffledResult.invalidCoordinates()).isEqualTo(baseline.invalidCoordinates());
            assertThat(shuffledResult.duplicates()).isEqualTo(baseline.duplicates());
            assertThat(shuffledResult.lowAccuracy()).isEqualTo(baseline.lowAccuracy());
            assertThat(shuffledResult.teleports()).isEqualTo(baseline.teleports());
        }
    }

    @Test
    @DisplayName("The input list passed to clean() must not be modified or reordered in-place")
    void inputListIsNotModified() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint p1 = new GpsPoint(12.9730, 77.5940, t0.plusSeconds(30), 10.0);
        GpsPoint p2 = new GpsPoint(12.9710, 77.5940, t0.plusSeconds(10), 10.0);

        List<GpsPoint> input = new ArrayList<>(List.of(p1, p2));
        List<GpsPoint> inputCopy = List.copyOf(input);

        cleaner.clean(input, defaultConfig);

        assertThat(input).isEqualTo(inputCopy);
    }

    @Test
    @DisplayName("Removal counts in CleaningResult must accurately reflect all filtered reasons")
    void removalCountsInCleaningResultAreAccurate() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");

        GpsPoint valid1 = new GpsPoint(12.9710, 77.5940, t0, 10.0);
        GpsPoint invalidCoord = new GpsPoint(999.0, 77.5940, t0.plusSeconds(10), 10.0); // 1 invalid
        GpsPoint duplicateOfValid1 = new GpsPoint(12.9710, 77.5940, t0, 20.0); // 1 duplicate
        GpsPoint lowAcc = new GpsPoint(12.9720, 77.5940, t0.plusSeconds(20), 80.0); // 1 low accuracy (>50m)
        GpsPoint teleport = new GpsPoint(14.0000, 77.5940, t0.plusSeconds(25), 10.0); // 1 teleport (huge jump in 25s)
        GpsPoint valid2 = new GpsPoint(12.9730, 77.5940, t0.plusSeconds(30), 10.0);

        CleaningResult result = cleaner.clean(
                List.of(valid1, invalidCoord, duplicateOfValid1, lowAcc, teleport, valid2),
                defaultConfig
        );

        assertThat(result.invalidCoordinates()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.lowAccuracy()).isEqualTo(1);
        assertThat(result.teleports()).isEqualTo(1);
        assertThat(result.cleanedPoints()).containsExactly(valid1, valid2);
    }
}

