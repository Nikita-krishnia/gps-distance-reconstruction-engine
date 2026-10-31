package com.example.gpsengine.benchmark;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.engine.MilestoneInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class SyntheticTripGeneratorTest {

    private final BenchmarkConfig defaultConfig = BenchmarkConfig.fromEnv();
    private final SyntheticTripGenerator generator = new SyntheticTripGenerator(defaultConfig);

    @Test
    @DisplayName("Generator is strictly reproducible: same (seed, index) gives identical trip")
    void sameSeedAndIndexGivesIdenticalTrip() {
        SyntheticTrip tripA = generator.generateTrip(1);
        SyntheticTrip tripB = generator.generateTrip(1);

        assertThat(tripA.tripIndex()).isEqualTo(tripB.tripIndex());
        assertThat(tripA.officePlannedLatitude()).isEqualTo(tripB.officePlannedLatitude());
        assertThat(tripA.officePlannedLongitude()).isEqualTo(tripB.officePlannedLongitude());
        assertThat(tripA.startTime()).isEqualTo(tripB.startTime());
        assertThat(tripA.endTime()).isEqualTo(tripB.endTime());
        assertThat(tripA.milestones()).isEqualTo(tripB.milestones());
        assertThat(tripA.rawPings()).isEqualTo(tripB.rawPings());
    }

    @Test
    @DisplayName("Different trip indices produce different distinct trips")
    void differentIndicesProduceDifferentTrips() {
        SyntheticTrip trip1 = generator.generateTrip(1);
        SyntheticTrip trip2 = generator.generateTrip(2);

        assertThat(trip1.rawPings()).isNotEqualTo(trip2.rawPings());
        assertThat(trip1.milestones()).isNotEqualTo(trip2.milestones());
    }

    @Test
    @DisplayName("Ping count is strictly within 5% of target across diverse target sizes")
    void pingCountWithin5PercentOfTarget() {
        int[] targets = {500, 1500, 3000, 6000};
        for (int target : targets) {
            for (int i = 1; i <= 5; i++) {
                SyntheticTrip trip = generator.generateTrip(i, target);
                int pings = trip.rawPings().size();
                int minAllowed = (int) Math.floor(target * 0.95);
                int maxAllowed = (int) Math.ceil(target * 1.05);

                assertThat(pings)
                        .as("Target %d for trip %d should be in [%d, %d], got %d", target, i, minAllowed, maxAllowed, pings)
                        .isBetween(minAllowed, maxAllowed);
            }
        }
    }

    @Test
    @DisplayName("Generated pings contain duplicates, out-of-order timestamps, low accuracy and GPS gaps")
    void tripContainsRealisticNoiseAndGaps() {
        // Sample several trips to assert noise characteristics
        boolean foundDuplicate = false;
        boolean foundDisorder = false;
        boolean foundLowAccuracy = false;
        boolean foundGpsGap = false;

        for (int i = 0; i < 10; i++) {
            SyntheticTrip trip = generator.generateTrip(i, 1500);
            List<GpsPoint> pings = trip.rawPings();

            // Check duplicates (same timestamp)
            Set<GpsPoint> set = new HashSet<>(pings);
            if (set.size() < pings.size()) {
                foundDuplicate = true;
            }

            // Check disorder (any ping earlier than previous)
            for (int p = 1; p < pings.size(); p++) {
                if (pings.get(p).timestamp().isBefore(pings.get(p - 1).timestamp())) {
                    foundDisorder = true;
                }
                // Check gap (>= 3 minutes between consecutive pings)
                long gapSec = Duration.between(pings.get(p - 1).timestamp(), pings.get(p).timestamp()).toSeconds();
                if (gapSec >= 180) {
                    foundGpsGap = true;
                }
            }

            // Check low accuracy (> 50m)
            for (GpsPoint pt : pings) {
                if (pt.accuracyMeters() != null && pt.accuracyMeters() > 50.0) {
                    foundLowAccuracy = true;
                }
            }
        }

        assertThat(foundDuplicate).as("Generator must produce duplicate pings").isTrue();
        assertThat(foundDisorder).as("Generator must produce out-of-order pings").isTrue();
        assertThat(foundLowAccuracy).as("Generator must produce low-accuracy fixes").isTrue();
        assertThat(foundGpsGap).as("Generator must produce multi-minute GPS gaps").isTrue();
    }

    @Test
    @DisplayName("Milestones contain TRIP_START, TRIP_END, intermediate drops and fallbacks")
    void milestonesFormValidBoundaryStructure() {
        SyntheticTrip trip = generator.generateTrip(1);
        List<MilestoneInput> milestones = trip.milestones();

        assertThat(milestones).isNotEmpty();
        assertThat(milestones.get(0).type()).isEqualTo(MilestoneType.TRIP_START);
        assertThat(milestones.get(milestones.size() - 1).type()).isEqualTo(MilestoneType.TRIP_END);

        long drops = milestones.stream().filter(m -> m.type() == MilestoneType.DROP).count();
        assertThat(drops).isGreaterThanOrEqualTo(2);
    }
}

