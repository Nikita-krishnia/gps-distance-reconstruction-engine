package com.example.gpsengine.engine;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import com.example.gpsengine.routing.FakeRoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class RandomizedTripInvariantTest {

    private static final Pattern FOUR_OR_MORE_DECIMALS = Pattern.compile("\\d+\\.\\d{4,}");

    private final GpsCleaner cleaner = new GpsCleaner();
    private final AnchorBuilder anchorBuilder = new AnchorBuilder();
    private final TripTimelineMerger timelineMerger = new TripTimelineMerger();
    private final DistanceEngine distanceEngine = new DistanceEngine();

    @Test
    @DisplayName("200 randomised trips verify all engine invariants and determinism")
    void randomizedTripInvariantsAcross200Seeds() {
        GpsCleaningConfig cleaningConfig = GpsCleaningConfig.defaults();
        AnchorConfig anchorConfig = AnchorConfig.defaults();
        SegmentConfig segmentConfig = SegmentConfig.defaults();
        FakeRoutingProvider fakeProvider = new FakeRoutingProvider(1.3);

        double officePlannedLat = 12.9279;
        double officePlannedLon = 77.6271;
        Instant baseTime = Instant.parse("2026-10-30T08:00:00Z");

        long startNanos = System.nanoTime();

        for (int seed = 1; seed <= 200; seed++) {
            Random rng = new Random(seed);

            // Generate a random synthetic trip
            int pingCount = 10 + rng.nextInt(40);
            List<GpsPoint> rawPings = new ArrayList<>(pingCount);

            double currentLat = 12.9716 + (rng.nextDouble() - 0.5) * 0.05;
            double currentLon = 77.5946 + (rng.nextDouble() - 0.5) * 0.05;
            Instant currentTime = baseTime;

            for (int p = 0; p < pingCount; p++) {
                int stepSeconds = 5 + rng.nextInt(1800); // 5s to 30 mins
                currentTime = currentTime.plusSeconds(stepSeconds);

                // Normal step or teleport jump
                boolean teleport = rng.nextDouble() < 0.08;
                if (teleport) {
                    currentLat += (rng.nextDouble() - 0.5) * 0.8;
                    currentLon += (rng.nextDouble() - 0.5) * 0.8;
                } else {
                    currentLat += (rng.nextDouble() - 0.5) * 0.005;
                    currentLon += (rng.nextDouble() - 0.5) * 0.005;
                }

                // Normal or degraded accuracy
                Double accuracy = rng.nextDouble() < 0.12 ? 55.0 + rng.nextDouble() * 100 : 5.0 + rng.nextDouble() * 30;
                rawPings.add(new GpsPoint(currentLat, currentLon, currentTime, accuracy));

                // Duplicate injection
                if (rng.nextDouble() < 0.15) {
                    rawPings.add(new GpsPoint(currentLat, currentLon, currentTime, accuracy));
                }
            }

            // Milestone anchors: 1..3 PICKUP/DROPs
            List<MilestoneInput> milestones = new ArrayList<>();
            int dropCount = 1 + rng.nextInt(3);
            for (int d = 0; d < dropCount; d++) {
                Instant dropTime = baseTime.plusSeconds(300 * (d + 1));
                double dropLat = 12.9500 + (rng.nextDouble() - 0.5) * 0.04;
                double dropLon = 77.6100 + (rng.nextDouble() - 0.5) * 0.04;
                boolean validActual = rng.nextDouble() > 0.2;

                milestones.add(new MilestoneInput(
                        MilestoneType.DROP,
                        "emp-" + seed + "-" + d,
                        RiderKind.PLANNED,
                        validActual ? dropLat : null, validActual ? dropLon : null, validActual ? dropTime : null,
                        dropLat, dropLon, dropTime,
                        null, null, null
                ));
            }

            Instant endTime = currentTime.plusSeconds(120);

            // Execute pipeline
            DistanceResult result1 = runEngine(rawPings, milestones, baseTime, endTime, officePlannedLat, officePlannedLon, cleaningConfig, anchorConfig, segmentConfig, fakeProvider);

            // Invariant 1: totalMeters equals sum of segment meters (as longs)
            long expectedSum = 0;
            for (SegmentResult s : result1.segments()) {
                expectedSum += s.meters();
            }
            assertThat(result1.totalMeters())
                    .as("Seed %d: totalMeters must equal sum of segment meters", seed)
                    .isEqualTo(expectedSum);

            // Invariant 2: sequence numbers are 1..n without gaps
            for (int i = 0; i < result1.segments().size(); i++) {
                assertThat(result1.segments().get(i).sequenceNumber())
                        .as("Seed %d: sequence numbers must be 1..n", seed)
                        .isEqualTo(i + 1);
            }

            // Invariant 3: rule non-null, reason non-blank, no coordinates with >=4 decimals in reason
            for (SegmentResult s : result1.segments()) {
                assertThat(s.rule()).as("Seed %d: rule non-null", seed).isNotNull();
                assertThat(s.reason()).as("Seed %d: reason non-blank", seed).isNotBlank();
                assertThat(FOUR_OR_MORE_DECIMALS.matcher(s.reason()).find())
                        .as("Seed %d: reason contains 4+ decimal coordinate '%s'", seed, s.reason())
                        .isFalse();
            }

            // Invariant 4: no negative meters; REJECTED_* segments have 0 meters
            for (SegmentResult s : result1.segments()) {
                assertThat(s.meters()).as("Seed %d: meters must be >= 0", seed).isGreaterThanOrEqualTo(0);
                if (s.rule().name().startsWith("REJECTED_")) {
                    assertThat(s.meters())
                            .as("Seed %d: REJECTED segment must have 0 meters", seed)
                            .isEqualTo(0);
                }
            }

            // Invariant 5: segments are contiguous and timestamps never go backwards
            for (int i = 0; i < result1.segments().size(); i++) {
                SegmentResult current = result1.segments().get(i);
                assertThat(current.from().timestamp())
                        .as("Seed %d: timestamp must not move backwards within segment", seed)
                        .isBeforeOrEqualTo(current.to().timestamp());

                if (i > 0) {
                    SegmentResult previous = result1.segments().get(i - 1);
                    assertThat(current.from())
                            .as("Seed %d: segment %d from point must match segment %d to point", seed, i + 1, i)
                            .isEqualTo(previous.to());
                }
            }

            // Invariant 6: running twice gives identical results (idempotence)
            DistanceResult result2 = runEngine(rawPings, milestones, baseTime, endTime, officePlannedLat, officePlannedLon, cleaningConfig, anchorConfig, segmentConfig, fakeProvider);
            assertThat(result2.totalMeters())
                    .as("Seed %d: run twice must match totalMeters", seed)
                    .isEqualTo(result1.totalMeters());
            assertThat(result2.segments())
                    .as("Seed %d: run twice must match segments exactly", seed)
                    .isEqualTo(result1.segments());

            // Invariant 7: shuffling raw input gives identical results
            List<GpsPoint> shuffledPings = new ArrayList<>(rawPings);
            Collections.shuffle(shuffledPings, rng);
            List<MilestoneInput> shuffledMilestones = new ArrayList<>(milestones);
            Collections.shuffle(shuffledMilestones, rng);
            DistanceResult resultShuffled = runEngine(shuffledPings, shuffledMilestones, baseTime, endTime, officePlannedLat, officePlannedLon, cleaningConfig, anchorConfig, segmentConfig, fakeProvider);
            assertThat(resultShuffled.totalMeters())
                    .as("Seed %d: shuffled input must give identical totalMeters", seed)
                    .isEqualTo(result1.totalMeters());

            // Invariant 8: adding extra exact-duplicate pings gives the same total
            List<GpsPoint> pingsWithExtraDups = new ArrayList<>(rawPings);
            pingsWithExtraDups.addAll(rawPings); // double all pings
            DistanceResult resultExtraDups = runEngine(pingsWithExtraDups, milestones, baseTime, endTime, officePlannedLat, officePlannedLon, cleaningConfig, anchorConfig, segmentConfig, fakeProvider);
            assertThat(resultExtraDups.totalMeters())
                    .as("Seed %d: extra duplicates must not alter totalMeters", seed)
                    .isEqualTo(result1.totalMeters());

            // Invariant 9: adding a 4-second burst 9 km away gives the same total (teleport filter removes it)
            // Insert burst after a known accepted ping where the gap to the next accepted ping is >= 30 seconds
            List<GpsPoint> cleanedPoints = cleaner.clean(rawPings, cleaningConfig).cleanedPoints();
            if (cleanedPoints.size() >= 2) {
                int validGapIndex = -1;
                for (int i = 0; i < cleanedPoints.size() - 1; i++) {
                    long sec = cleanedPoints.get(i + 1).timestamp().getEpochSecond() - cleanedPoints.get(i).timestamp().getEpochSecond();
                    if (sec >= 30) {
                        validGapIndex = i;
                        break;
                    }
                }
                if (validGapIndex >= 0) {
                    GpsPoint acceptedAnchor = cleanedPoints.get(validGapIndex);
                    Instant burstTime = acceptedAnchor.timestamp().plusSeconds(2);
                    // 9 km jump north (0.081 degrees latitude)
                    double jumpLat = acceptedAnchor.latitude() + 0.081;
                    double jumpLon = acceptedAnchor.longitude();

                    List<GpsPoint> pingsWithTeleportBurst = new ArrayList<>(rawPings);
                    pingsWithTeleportBurst.add(new GpsPoint(jumpLat, jumpLon, burstTime, 10.0));
                    pingsWithTeleportBurst.add(new GpsPoint(jumpLat + 0.0001, jumpLon, burstTime.plusSeconds(2), 10.0));
                    pingsWithTeleportBurst.add(new GpsPoint(jumpLat + 0.0002, jumpLon, burstTime.plusSeconds(4), 10.0));

                    DistanceResult resultTeleportBurst = runEngine(pingsWithTeleportBurst, milestones, baseTime, endTime, officePlannedLat, officePlannedLon, cleaningConfig, anchorConfig, segmentConfig, fakeProvider);
                    assertThat(resultTeleportBurst.totalMeters())
                            .as("Seed %d: teleport burst 9km away in 4s must be dropped without affecting totalMeters", seed)
                            .isEqualTo(result1.totalMeters());
                }
            }
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        assertThat(elapsedMillis)
                .as("Randomised invariant suite took %d ms (expected < 10000 ms)", elapsedMillis)
                .isLessThan(10000);
    }

    private DistanceResult runEngine(List<GpsPoint> rawPings,
                                     List<MilestoneInput> milestones,
                                     Instant startTime,
                                     Instant endTime,
                                     double officePlannedLat,
                                     double officePlannedLon,
                                     GpsCleaningConfig cleaningConfig,
                                     AnchorConfig anchorConfig,
                                     SegmentConfig segmentConfig,
                                     FakeRoutingProvider fakeProvider) {
        CleaningResult cleaningResult = cleaner.clean(rawPings, cleaningConfig);
        AnchorResult anchorResult = anchorBuilder.buildAnchors(
                milestones,
                startTime,
                endTime,
                officePlannedLat,
                officePlannedLon,
                officePlannedLat,
                officePlannedLon,
                anchorConfig
        );
        List<TripPoint> timeline = timelineMerger.merge(cleaningResult.cleanedPoints(), anchorResult.anchors());
        return distanceEngine.calculate(timeline, fakeProvider, segmentConfig);
    }
}
