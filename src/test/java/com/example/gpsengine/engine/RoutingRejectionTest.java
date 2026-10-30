package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RouteSource;
import com.example.gpsengine.routing.RoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class RoutingRejectionTest {

    private final DistanceEngine engine = new DistanceEngine();
    private final SegmentConfig config = SegmentConfig.defaults(); // maxRouteMeters=100000, maxSpeedKmh=120, fallbackMult=1.4

    @Test
    @DisplayName("Max route boundary: exactly 100,000m is accepted as ROUTED; 100,001m is REJECTED_MAX_DISTANCE contributing 0m")
    void maxRouteBoundaryCheck() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        // Time gap of 2 hours (7200s) so 100km gives speed 50 km/h (plausible)
        Instant t1 = t0.plusSeconds(7200);

        TripPoint from = TripPoint.fromGps(new GpsPoint(12.0, 77.0, t0, 10.0));
        TripPoint to = TripPoint.fromGps(new GpsPoint(12.5, 77.5, t1, 10.0));

        // Test 1: exactly 100,000m
        RoutingProvider provider100k = (lat1, lon1, lat2, lon2) -> new RouteResult(100000.0, RouteSource.PROVIDER);
        DistanceResult res100k = engine.calculate(List.of(from, to), provider100k, config);

        assertThat(res100k.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(res100k.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(res100k.segments().get(0).meters()).isEqualTo(100000L);
        assertThat(res100k.totalMeters()).isEqualTo(100000L);

        // Test 2: exactly 100,001m
        RoutingProvider provider100kPlus1 = (lat1, lon1, lat2, lon2) -> new RouteResult(100001.0, RouteSource.PROVIDER);
        DistanceResult res100kPlus1 = engine.calculate(List.of(from, to), provider100kPlus1, config);

        assertThat(res100kPlus1.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(res100kPlus1.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_MAX_DISTANCE);
        assertThat(res100kPlus1.segments().get(0).meters()).isEqualTo(0L);
        assertThat(res100kPlus1.totalMeters()).isEqualTo(0L);
        assertThat(res100kPlus1.segments().get(0).reason()).contains("exceeds maximum route limit");
    }

    @Test
    @DisplayName("Straight-line aerial pre-check avoids provider call when straight-line speed exceeds 120 km/h")
    void straightLineSpeedPreCheckAvoidsProviderCall() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        // In 100 seconds (not a short gap >= 70s), vehicle jumps 10 km (~12.0 to 12.09 degrees lat)
        // Straight line speed = 10km / (100/3600)h = 360 km/h > 120 km/h
        Instant t1 = t0.plusSeconds(100);

        TripPoint from = TripPoint.fromGps(new GpsPoint(12.0000, 77.0000, t0, 10.0));
        TripPoint to = TripPoint.fromGps(new GpsPoint(12.0900, 77.0000, t1, 10.0));

        AtomicInteger callCount = new AtomicInteger();
        RoutingProvider countingProvider = (lat1, lon1, lat2, lon2) -> {
            callCount.incrementAndGet();
            return new RouteResult(11000.0, RouteSource.PROVIDER);
        };

        DistanceResult result = engine.calculate(List.of(from, to), countingProvider, config);

        assertThat(callCount.get()).as("Provider must not be queried when straight-line pre-check fails").isEqualTo(0);
        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(result.segments().get(0).meters()).isEqualTo(0L);
        assertThat(result.totalMeters()).isEqualTo(0L);
        assertThat(result.segments().get(0).reason()).contains("Straight-line speed");
    }

    @Test
    @DisplayName("Speed rejection requires BOTH conditions: routed speed > limit AND straight*1.4 speed > limit")
    void speedRejectionDualCondition() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        // Time gap = 120s (2 minutes). Max allowed distance at 120 km/h = 120 * (120/3600) = 4000 meters.
        Instant t1 = t0.plusSeconds(120);

        // Straight-line distance = 2000m (straight speed = 60 km/h <= 120 km/h, passes pre-check)
        // straight * 1.4 = 2800m (straight * 1.4 speed = 84 km/h <= 120 km/h)
        TripPoint from = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));
        double deltaLat2000m = (2000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint to = TripPoint.fromGps(new GpsPoint(deltaLat2000m, 0.0, t1, 10.0));

        // Condition A: routed distance = 5000m (routed speed = 150 km/h > 120 km/h),
        // BUT straight*1.4 speed = 84 km/h <= 120 km/h.
        // Because straight*1.4 is plausible, this is NOT speed-rejected; it stays ROUTED!
        RoutingProvider providerCircuitous = (lat1, lon1, lat2, lon2) -> new RouteResult(5000.0, RouteSource.PROVIDER);
        DistanceResult resultA = engine.calculate(List.of(from, to), providerCircuitous, config);

        assertThat(resultA.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(resultA.segments().get(0).meters()).isEqualTo(5000L);
        assertThat(resultA.status()).isEqualTo(TripStatus.CALCULATED);

        // Condition B: time gap = 72s.
        // Straight-line distance = 2200m -> straight speed = 110 km/h (passes pre-check <= 120 km/h).
        // straight * 1.4 = 3080m -> straight*1.4 speed = (3.08 / (72/3600)) = 154 km/h > 120 km/h!
        // Routed distance = 3500m -> routed speed = 175 km/h > 120 km/h!
        // BOTH exceed 120 km/h -> REJECTED_SPEED!
        Instant t72s = t0.plusSeconds(72);
        double deltaLat2200m = (2200.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint to72s = TripPoint.fromGps(new GpsPoint(deltaLat2200m, 0.0, t72s, 10.0));

        RoutingProvider providerExcessive = (lat1, lon1, lat2, lon2) -> new RouteResult(3500.0, RouteSource.PROVIDER);
        DistanceResult resultB = engine.calculate(List.of(from, to72s), providerExcessive, config);

        assertThat(resultB.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(resultB.segments().get(0).meters()).isEqualTo(0L);
        assertThat(resultB.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(resultB.segments().get(0).reason()).contains("both exceed limit");
    }

    @Test
    @DisplayName("Trip with rejected segments remains CALCULATED (not DEGRADED) and totalMeters equals sum of valid segments")
    void rejectedSegmentsKeepTripCalculatedWithZeroContribution() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");

        // Segment 1: Short gap -> 500m SHORT_GAP_AERIAL
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.0000, 77.0000, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.0040, 77.0000, t0.plusSeconds(30), 10.0));

        // Segment 2: Speed pre-check rejected -> 0m REJECTED_SPEED (gap 100s, jump 20km)
        TripPoint p3 = TripPoint.fromGps(new GpsPoint(12.1800, 77.0000, t0.plusSeconds(130), 10.0));

        // Segment 3: Valid routed -> 2000m ROUTED (gap 300s, 2km)
        TripPoint p4 = TripPoint.fromGps(new GpsPoint(12.1980, 77.0000, t0.plusSeconds(430), 10.0));

        RoutingProvider provider = (lat1, lon1, lat2, lon2) -> new RouteResult(2000.0, RouteSource.PROVIDER);
        DistanceResult result = engine.calculate(List.of(p1, p2, p3, p4), provider, config);

        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(result.segments()).hasSize(3);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
        assertThat(result.segments().get(1).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(result.segments().get(1).meters()).isEqualTo(0L);
        assertThat(result.segments().get(2).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(result.segments().get(2).meters()).isEqualTo(2000L);

        long sum = result.segments().get(0).meters() + 0L + 2000L;
        assertThat(result.totalMeters()).isEqualTo(sum);
    }
}

