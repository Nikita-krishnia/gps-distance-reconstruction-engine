package com.example.gpsengine.benchmark;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.engine.HaversineCalculator;
import com.example.gpsengine.engine.MilestoneInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic, streamable synthetic trip generator.
 *
 * <p>Produces realistic urban commuter trips with fixed noise rates, GPS gaps,
 * employee milestones with fallbacks, and realistic speed profiles.
 * Any trip is 100% reproducible from {@code (baseSeed, tripIndex)}.
 */
public class SyntheticTripGenerator {

    private final BenchmarkConfig config;
    private final WorldModel worldModel;

    public SyntheticTripGenerator(BenchmarkConfig config) {
        this.config = config;
        this.worldModel = new WorldModel(config.places(), config.seed());
    }

    public SyntheticTripGenerator(long seed) {
        this(new BenchmarkConfig(
                BenchmarkConfig.DEFAULT_TRIPS,
                BenchmarkConfig.DEFAULT_PINGS,
                BenchmarkConfig.DEFAULT_THREADS,
                BenchmarkConfig.DEFAULT_PLACES,
                BenchmarkConfig.DEFAULT_REPEATS,
                BenchmarkConfig.DEFAULT_E2E_TRIPS,
                BenchmarkConfig.DEFAULT_E2E_THREADS,
                BenchmarkConfig.DEFAULT_SIM_LATENCY_MS,
                BenchmarkConfig.DEFAULT_SIM_TRIPS,
                seed
        ));
    }

    public WorldModel getWorldModel() {
        return worldModel;
    }

    /**
     * Generates a single synthetic trip for the given tripIndex.
     */
    public SyntheticTrip generateTrip(int tripIndex) {
        return generateTrip(tripIndex, config.pings());
    }

    /**
     * Generates a single synthetic trip with an explicit target ping count.
     */
    public SyntheticTrip generateTrip(int tripIndex, int targetPings) {
        SplittableRandom rng = new SplittableRandom(config.seed() ^ ((long) tripIndex * 31337L + 101L));

        WorldModel.Place officeHub = worldModel.getOfficeHub();
        double officePlannedLat = officeHub.latitude();
        double officePlannedLon = officeHub.longitude();

        // 1. Choose route sequence of 6 to 10 places (5 to 9 legs)
        int numPlaces = 6 + rng.nextInt(5);
        List<WorldModel.Place> routePlaces = new ArrayList<>(numPlaces);
        routePlaces.add(worldModel.samplePlace(rng)); // Origin
        for (int i = 1; i < numPlaces - 1; i++) {
            routePlaces.add(worldModel.samplePlace(rng));
        }
        routePlaces.add(officeHub); // Destination is the corporate office hub

        // Target pings distributed across legs
        // Allocate budget for noise: ~2% duplicates, bursts of teleports (~10 pings)
        int noiseBudget = (int) Math.round(targetPings * 0.02) + 10;
        int basePingsNeeded = Math.max(50, targetPings - noiseBudget);
        int numLegs = numPlaces - 1;
        int pingsPerLeg = Math.max(10, basePingsNeeded / numLegs);

        Instant currentTime = Instant.parse("2026-10-30T06:00:00Z").plusSeconds((long) tripIndex * 180L);
        Instant tripStartTime = currentTime;

        List<GpsPoint> pings = new ArrayList<>(targetPings + 100);
        List<MilestoneInput> milestones = new ArrayList<>();

        // Add TRIP_START milestone
        milestones.add(new MilestoneInput(
                MilestoneType.TRIP_START,
                null,
                null,
                null, null, tripStartTime,
                null, null, null,
                null, null, null
        ));

        // 2. Decide on 2 to 4 GPS gaps of 3 to 20 minutes (180 to 1200 seconds)
        int numGaps = 2 + rng.nextInt(3); // 2, 3, or 4 gaps
        boolean[] legHasGap = new boolean[numLegs];
        int gapsAssigned = 0;
        for (int leg = 1; leg < numLegs && gapsAssigned < numGaps; leg++) {
            legHasGap[leg] = true;
            gapsAssigned++;
        }

        // Track employee drops to synthesize
        int dropMilestoneCount = 2 + rng.nextInt(3); // 2, 3, or 4 drops
        int dropsCreated = 0;

        // 3. Generate legs
        for (int leg = 0; leg < numLegs; leg++) {
            WorldModel.Place startPlace = routePlaces.get(leg);
            WorldModel.Place endPlace = routePlaces.get(leg + 1);

            double fromLat = startPlace.latitude();
            double fromLon = startPlace.longitude();
            double toLat = endPlace.latitude();
            double toLon = endPlace.longitude();

            // Gap check
            if (legHasGap[leg]) {
                // Gap starts near place coordinates with ~15m jitter
                double jitterLat = fromLat + ((rng.nextDouble() - 0.5) * 0.00027); // ~15m
                double jitterLon = fromLon + ((rng.nextDouble() - 0.5) * 0.00027);
                pings.add(new GpsPoint(jitterLat, jitterLon, currentTime, 10.0 + rng.nextDouble() * 15.0));

                int gapSeconds = 180 + rng.nextInt(1021); // 3 to 20 minutes (180s to 1200s)

                // If drops needed, place a drop inside this gap!
                if (dropsCreated < dropMilestoneCount) {
                    dropsCreated++;
                    String empRef = "emp-" + tripIndex + "-" + dropsCreated;
                    Instant dropTime = currentTime.plusSeconds(gapSeconds / 2);

                    // Interpolate position inside gap
                    double dropLat = fromLat + 0.5 * (toLat - fromLat);
                    double dropLon = fromLon + 0.5 * (toLon - fromLon);

                    boolean invalidActual = (dropsCreated % 2 == 1);
                    if (invalidActual) {
                        // Invalid actual location (0,0) -> triggers fallback!
                        milestones.add(new MilestoneInput(
                                MilestoneType.DROP,
                                empRef,
                                RiderKind.PLANNED,
                                0.0, 0.0, dropTime,
                                dropLat, dropLon, dropTime, // planned
                                null, null, null
                        ));
                    } else {
                        // Valid actual location
                        milestones.add(new MilestoneInput(
                                MilestoneType.DROP,
                                empRef,
                                RiderKind.PLANNED,
                                dropLat, dropLon, dropTime,
                                dropLat, dropLon, dropTime,
                                null, null, null
                        ));
                    }
                }

                currentTime = currentTime.plusSeconds(gapSeconds);

                // Gap ends near destination with ~15m jitter
                double endJitterLat = toLat + ((rng.nextDouble() - 0.5) * 0.00027);
                double endJitterLon = toLon + ((rng.nextDouble() - 0.5) * 0.00027);
                pings.add(new GpsPoint(endJitterLat, endJitterLon, currentTime, 10.0 + rng.nextDouble() * 15.0));
                continue;
            }

            // Normal leg: emit pings at 20 to 50 km/h with 4 to 6s interval
            int legPings = pingsPerLeg;
            for (int p = 0; p < legPings; p++) {
                double frac = (double) p / Math.max(1, legPings - 1);
                double interpLat = fromLat + frac * (toLat - fromLat);
                double interpLon = fromLon + frac * (toLon - fromLon);

                // Small road-like perpendicular wiggle (~5 to 15m)
                double wiggle = (rng.nextDouble() - 0.5) * 0.00015;
                double pingLat = interpLat + wiggle;
                double pingLon = interpLon + wiggle;

                // Accuracy 3 to 30 m
                double accuracy = 3.0 + rng.nextDouble() * 27.0;

                pings.add(new GpsPoint(pingLat, pingLon, currentTime, accuracy));
                int stepSeconds = 4 + rng.nextInt(3); // 4, 5, or 6 seconds
                currentTime = currentTime.plusSeconds(stepSeconds);
            }

            // Intermediate drop milestone outside gap
            if (dropsCreated < dropMilestoneCount && rng.nextDouble() < 0.6) {
                dropsCreated++;
                String empRef = "emp-" + tripIndex + "-" + dropsCreated;
                milestones.add(new MilestoneInput(
                        MilestoneType.DROP,
                        empRef,
                        RiderKind.PLANNED,
                        toLat, toLon, currentTime,
                        toLat, toLon, currentTime,
                        null, null, null
                ));
            }
        }

        // Occasional NO_SHOW (1 in 10 trips)
        if (tripIndex % 10 == 7) {
            milestones.add(new MilestoneInput(
                    MilestoneType.NO_SHOW,
                    "emp-noshow-" + tripIndex,
                    RiderKind.PLANNED,
                    null, null, null,
                    officePlannedLat + 0.005, officePlannedLon + 0.005, currentTime,
                    null, null, null
            ));
        }

        // Add TRIP_END milestone
        Instant tripEndTime = currentTime.plusSeconds(30);
        double actualEndLat;
        double actualEndLon;
        // ~70% trips within 400m of office, ~30% trips 450m to 900m away
        if (rng.nextDouble() < 0.70) {
            // Within 400m (~0.0025 deg)
            actualEndLat = officePlannedLat + ((rng.nextDouble() - 0.5) * 0.0025);
            actualEndLon = officePlannedLon + ((rng.nextDouble() - 0.5) * 0.0025);
        } else {
            // 450m to 900m away (~0.005 to 0.008 deg)
            double angle = rng.nextDouble() * 2 * Math.PI;
            double distanceDeg = 0.005 + rng.nextDouble() * 0.003;
            actualEndLat = officePlannedLat + Math.cos(angle) * distanceDeg;
            actualEndLon = officePlannedLon + Math.sin(angle) * distanceDeg;
        }

        milestones.add(new MilestoneInput(
                MilestoneType.TRIP_END,
                null,
                null,
                actualEndLat, actualEndLon, tripEndTime,
                officePlannedLat, officePlannedLon, tripEndTime,
                null, null, null
        ));

        // 4. Inject Noise
        // A. Low-accuracy fixes (~1% worse than 50m)
        int lowAccCount = Math.max(1, (int) Math.round(pings.size() * 0.01));
        for (int i = 0; i < lowAccCount; i++) {
            int idx = rng.nextInt(pings.size());
            GpsPoint old = pings.get(idx);
            double badAcc = 55.0 + rng.nextDouble() * 65.0; // 55m to 120m
            pings.set(idx, new GpsPoint(old.latitude(), old.longitude(), old.timestamp(), badAcc));
        }

        // B. Teleport bursts (0 to 2 bursts of 3 to 6 pings 5 to 15 km away within a few seconds)
        int teleportBursts = rng.nextInt(3); // 0, 1, or 2
        for (int b = 0; b < teleportBursts; b++) {
            int burstSize = 3 + rng.nextInt(4); // 3 to 6
            int insertIdx = rng.nextInt(Math.max(1, pings.size() - 10));
            Instant burstTime = pings.get(insertIdx).timestamp().plusSeconds(1);

            // Teleport 5 to 15 km away (~0.045 to 0.135 degrees)
            double telLat = pings.get(insertIdx).latitude() + 0.06 + (rng.nextDouble() * 0.06);
            double telLon = pings.get(insertIdx).longitude() + 0.06 + (rng.nextDouble() * 0.06);

            for (int k = 0; k < burstSize; k++) {
                pings.add(insertIdx + k + 1, new GpsPoint(
                        telLat + ((rng.nextDouble() - 0.5) * 0.0005),
                        telLon + ((rng.nextDouble() - 0.5) * 0.0005),
                        burstTime.plusSeconds(k),
                        10.0
                ));
            }
        }

        // C. Duplicates (~2% exact duplicates with same timestamp and coords rounded to 6 decimals)
        int dupCount = Math.max(1, (int) Math.round(pings.size() * 0.02));
        for (int i = 0; i < dupCount; i++) {
            int srcIdx = rng.nextInt(pings.size());
            GpsPoint src = pings.get(srcIdx);
            // Same coordinates to 6 decimals
            double dupLat = Math.round(src.latitude() * 1_000_000.0) / 1_000_000.0;
            double dupLon = Math.round(src.longitude() * 1_000_000.0) / 1_000_000.0;
            double dupAcc = (i % 2 == 0) ? src.accuracyMeters() : 35.0;
            pings.add(new GpsPoint(dupLat, dupLon, src.timestamp(), dupAcc));
        }

        // D. Out-of-order delivery (~1% swap neighbor pings)
        int disorderCount = Math.max(1, (int) Math.round(pings.size() * 0.01));
        for (int i = 0; i < disorderCount; i++) {
            int idx = rng.nextInt(Math.max(1, pings.size() - 2));
            GpsPoint p1 = pings.get(idx);
            GpsPoint p2 = pings.get(idx + 1);
            pings.set(idx, p2);
            pings.set(idx + 1, p1);
        }

        // 5. Adjust total pings to strictly fall within +/- 5% of targetPings
        int minPings = (int) Math.floor(targetPings * 0.95);
        int maxPings = (int) Math.ceil(targetPings * 1.05);

        while (pings.size() > maxPings) {
            pings.remove(pings.size() - 1);
        }
        while (pings.size() < minPings) {
            GpsPoint last = pings.get(pings.size() - 1);
            pings.add(new GpsPoint(
                    last.latitude() + 0.00005,
                    last.longitude() + 0.00005,
                    last.timestamp().plusSeconds(5),
                    12.0
            ));
        }

        return new SyntheticTrip(
                tripIndex,
                officePlannedLat,
                officePlannedLon,
                tripStartTime,
                tripEndTime,
                Collections.unmodifiableList(milestones),
                Collections.unmodifiableList(pings)
        );
    }
}

