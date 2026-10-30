package com.example.gpsengine.routing;

import com.example.gpsengine.engine.DistanceEngine;
import com.example.gpsengine.engine.PointOrigin;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.TripPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class CachingDemonstrationTest {

    @Test
    @DisplayName("Demonstration: running synthetic trips with cache makes significantly fewer provider calls")
    void cachingSignificantlyReducesProviderCalls() {
        // Seeded random for deterministic corridor reuse
        Random rng = new Random(42);
        int tripCount = 200;
        List<List<TripPoint>> trips = new ArrayList<>(tripCount);

        Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

        // 5 distinct simulated arterial corridors (e.g. Electronic City to Whitefield corridors)
        double[][] corridors = new double[][]{
                {12.8399, 77.6770, 12.9698, 77.7499},
                {12.9279, 77.6271, 12.9716, 77.5946},
                {12.9783, 77.6408, 13.0358, 77.5970},
                {13.0033, 77.5645, 12.9352, 77.6245},
                {12.9081, 77.6519, 12.9716, 77.5946}
        };

        for (int i = 0; i < tripCount; i++) {
            int corridorIndex = rng.nextInt(corridors.length);
            double[] base = corridors[corridorIndex];

            // Small jitter within 0.0003 degrees (~30m), snapping to same grid cells
            double j1 = (rng.nextDouble() - 0.5) * 0.0004;
            double j2 = (rng.nextDouble() - 0.5) * 0.0004;

            TripPoint p1 = new TripPoint(base[0] + j1, base[1] + j1, t0, PointOrigin.GPS, null);
            // 900 seconds (15 mins) for ~16 km = ~64 km/h plausible highway driving speed
            TripPoint p2 = new TripPoint(base[2] + j2, base[3] + j2, t0.plusSeconds(900), PointOrigin.GPS, null);
            trips.add(List.of(p1, p2));
        }

        DistanceEngine engine = new DistanceEngine();

        // 1. Run WITHOUT cache (pure raw provider)
        FakeRoutingProvider rawWithoutCache = new FakeRoutingProvider(1.3);
        for (List<TripPoint> trip : trips) {
            engine.calculate(trip, rawWithoutCache, SegmentConfig.defaults());
        }
        int callsWithoutCache = rawWithoutCache.getCallCount();

        // 2. Run WITH cache
        FakeRoutingProvider rawWithCache = new FakeRoutingProvider(1.3);
        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                rawWithCache, 10000, Duration.ofHours(24), 0.001, "v1", stats
        );
        for (List<TripPoint> trip : trips) {
            engine.calculate(trip, cachingProvider, SegmentConfig.defaults());
        }
        int callsWithCache = rawWithCache.getCallCount();

        // Informational logging for inspection
        System.out.println("================================================================================");
        System.out.println("[DEMONSTRATION ONLY - NOT BATCH 12 BENCHMARK]");
        System.out.println("Synthetic trips processed: " + tripCount);
        System.out.println("Provider calls WITHOUT cache: " + callsWithoutCache);
        System.out.println("Provider calls WITH cache:    " + callsWithCache);
        System.out.println("Cache hits: " + stats.snapshot().cacheHits() + ", misses: " + stats.snapshot().cacheMisses());
        System.out.println("Call reduction: " + String.format("%.1f%%", (1.0 - (double) callsWithCache / callsWithoutCache) * 100.0));
        System.out.println("================================================================================");

        assertThat(callsWithCache).isLessThan(callsWithoutCache);
        assertThat(callsWithoutCache).isGreaterThan(0);
    }
}

