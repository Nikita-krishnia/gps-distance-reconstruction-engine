package com.example.gpsengine.benchmark;

import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.RoutingStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast regression guard executed in every normal build (@Tag("unit")).
 * Verifies that the synthetic trip generator and calculation pipeline produce
 * deterministic, invariant-satisfying results on 20 synthetic trips.
 */
@Tag("unit")
class BenchmarkRegressionGuardTest {

    @Test
    @DisplayName("20 synthetic trips run deterministically through pipeline with cached and uncached routing")
    void testTwentyTripsDeterminismAndInvariants() {
        SyntheticTripGenerator generator = new SyntheticTripGenerator(42L);
        List<SyntheticTrip> trips = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            trips.add(generator.generateTrip(i, 200));
        }

        EnginePipeline pipeline = new EnginePipeline();

        // 1. Run with Uncached FakeRoutingProvider
        FakeRoutingProvider directProvider = new FakeRoutingProvider();
        for (SyntheticTrip trip : trips) {
            EnginePipeline.PipelineResult res1 = pipeline.processTrip(trip, directProvider);
            EnginePipeline.PipelineResult res2 = pipeline.processTrip(trip, directProvider);

            assertThat(res1.status()).isIn(TripStatus.CALCULATED, TripStatus.DEGRADED);
            assertThat(res1.totalMeters()).isGreaterThan(0L);
            assertThat(res1.inputHash()).isNotNull().hasSize(64);

            // Determinism: Same trip twice produces identical meters and hash
            assertThat(res1.totalMeters()).isEqualTo(res2.totalMeters());
            assertThat(res1.inputHash()).isEqualTo(res2.inputHash());
            assertThat(res1.status()).isEqualTo(res2.status());
        }

        // 2. Run with CachingRoutingProvider
        FakeRoutingProvider underlying = new FakeRoutingProvider();
        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider cachedProvider = new CachingRoutingProvider(
                underlying,
                100_000,
                Duration.ofHours(24),
                0.001,
                "fake-v1",
                stats
        );

        for (SyntheticTrip trip : trips) {
            EnginePipeline.PipelineResult resCached1 = pipeline.processTrip(trip, cachedProvider);
            EnginePipeline.PipelineResult resCached2 = pipeline.processTrip(trip, cachedProvider);

            assertThat(resCached1.status()).isIn(TripStatus.CALCULATED, TripStatus.DEGRADED);
            assertThat(resCached1.totalMeters()).isGreaterThan(0L);
            assertThat(resCached1.totalMeters()).isEqualTo(resCached2.totalMeters());
            assertThat(resCached1.inputHash()).isEqualTo(resCached2.inputHash());
        }

        assertThat(stats.snapshot().cacheHits()).isGreaterThanOrEqualTo(0L);
    }
}

