package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DistanceEngineTest {

    private final DistanceEngine engine = new DistanceEngine();
    private final SegmentConfig defaultConfig = SegmentConfig.defaults();

    @Test
    @DisplayName("Short gap: 69s at plausible speed -> SHORT_GAP_AERIAL; exactly 70s is NOT a short gap")
    void shortGapThresholdRules() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.9710, 77.5940, t0, 10.0));
        // 69 seconds gap, distance ~500m (speed ~26 km/h)
        TripPoint p69s = TripPoint.fromGps(new GpsPoint(12.9755, 77.5940, t0.plusSeconds(69), 10.0));

        DistanceResult result69s = engine.calculate(List.of(p1, p69s), (lat1, lon1, lat2, lon2) -> new RouteResult(600.0), defaultConfig);
        assertThat(result69s.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
        assertThat(result69s.providerCalls()).isEqualTo(0); // Short gap does not query provider

        // Exactly 70 seconds gap, speed ~26 km/h (not parked, not short gap) -> must query provider
        TripPoint p70s = TripPoint.fromGps(new GpsPoint(12.9755, 77.5940, t0.plusSeconds(70), 10.0));
        DistanceResult result70s = engine.calculate(List.of(p1, p70s), (lat1, lon1, lat2, lon2) -> new RouteResult(600.0), defaultConfig);
        assertThat(result70s.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(result70s.providerCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Short gap speed boundary: speed exactly at limit (120 km/h) allowed as SHORT_GAP_AERIAL; just above is rejected")
    void shortGapSpeedBoundary() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));

        // In 30 seconds, 120 km/h covers: 120 * 1000 / 3600 * 30 = 1000.0 metres
        double deltaLat400m = (1000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);

        // Point exactly at limit (1000m in 30s = 120.0 km/h)
        TripPoint pBoundary = TripPoint.fromGps(new GpsPoint(deltaLat400m - 1e-11, 0.0, t0.plusSeconds(30), 10.0));
        DistanceResult boundaryResult = engine.calculate(List.of(p1, pBoundary), null, defaultConfig);
        assertThat(boundaryResult.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);

        // Point just above limit (1002m in 30s > 120.0 km/h) -> fails short gap, hits pre-check
        double deltaLatAbove = (1002.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint pAbove = TripPoint.fromGps(new GpsPoint(deltaLatAbove, 0.0, t0.plusSeconds(30), 10.0));
        DistanceResult aboveResult = engine.calculate(List.of(p1, pAbove), null, defaultConfig);
        assertThat(aboveResult.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
    }

    @Test
    @DisplayName("Parked: long gap with speed 4.9 km/h is PARKED; exactly 5.0 km/h is NOT parked; short gap is SHORT_GAP_AERIAL")
    void parkedRules() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));

        // Gap = 3600 seconds (1 hour)
        // 4.9 km/h covers 4900 metres
        double deltaLat4_9km = (4900.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint pParked = TripPoint.fromGps(new GpsPoint(deltaLat4_9km, 0.0, t0.plusSeconds(3600), 10.0));
        DistanceResult parkedResult = engine.calculate(List.of(p1, pParked), null, defaultConfig);
        assertThat(parkedResult.segments().get(0).rule()).isEqualTo(SegmentRule.PARKED);

        // Exactly 5.0 km/h covers 5000 metres in 1 hour -> not parked! (calls provider)
        double deltaLat5km = (5000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint p5kmh = TripPoint.fromGps(new GpsPoint(deltaLat5km, 0.0, t0.plusSeconds(3600), 10.0));
        DistanceResult notParkedResult = engine.calculate(List.of(p1, p5kmh), (lat1, lon1, lat2, lon2) -> new RouteResult(5200.0), defaultConfig);
        assertThat(notParkedResult.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);

        // Slow movement under 70 seconds: short gap takes priority over parked
        TripPoint pSlowShort = TripPoint.fromGps(new GpsPoint(deltaLat4_9km / 100.0, 0.0, t0.plusSeconds(30), 10.0));
        DistanceResult shortSlowResult = engine.calculate(List.of(p1, pSlowShort), null, defaultConfig);
        assertThat(shortSlowResult.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
    }

    @Test
    @DisplayName("Routed: long gap at normal speed queries provider and accepts road distance")
    void routedSegmentAccepted() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.9200, 77.5800, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.9500, 77.5900, t0.plusSeconds(300), 10.0)); // 5 mins gap

        DistanceResult result = engine.calculate(
                List.of(p1, p2),
                (lat1, lon1, lat2, lon2) -> new RouteResult(4250.0),
                defaultConfig
        );

        assertThat(result.providerCalls()).isEqualTo(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(result.segments().get(0).meters()).isEqualTo(4250L);
        assertThat(result.totalMeters()).isEqualTo(4250L);
        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
    }

    @Test
    @DisplayName("Max route distance: routed distance exactly 100,000m accepted; 100,001m rejected as REJECTED_MAX_DISTANCE")
    void maxRouteDistanceRules() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.0, 77.0, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.5, 77.5, t0.plusSeconds(7200), 10.0)); // 2 hours

        DistanceResult boundaryResult = engine.calculate(
                List.of(p1, p2),
                (lat1, lon1, lat2, lon2) -> new RouteResult(100_000.0),
                defaultConfig
        );
        assertThat(boundaryResult.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(boundaryResult.segments().get(0).meters()).isEqualTo(100_000L);

        DistanceResult exceededResult = engine.calculate(
                List.of(p1, p2),
                (lat1, lon1, lat2, lon2) -> new RouteResult(100_001.0),
                defaultConfig
        );
        assertThat(exceededResult.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_MAX_DISTANCE);
        assertThat(exceededResult.segments().get(0).meters()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Speed rule literal: rejected only if BOTH routed speed > max AND straight*1.4 speed > max")
    void speedRuleLiteralEvaluation() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        // Time gap = 0.5 hours (1800 seconds)
        // Straight line = 35 km (35,000 m). Straight * 1.4 = 49 km -> speed 49 / 0.5 = 98 km/h <= 120 km/h (within limit!)
        double deltaLat35km = (35_000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(deltaLat35km, 0.0, t0.plusSeconds(1800), 10.0));

        // Case A: Routed distance = 65 km in 0.5h -> speed = 130 km/h (> 120), BUT straight*1.4 speed = 98 km/h (<= 120).
        // Since BOTH are not exceeding limit, it is accepted as ROUTED!
        DistanceResult resultAccepted = engine.calculate(
                List.of(p1, p2),
                (lat1, lon1, lat2, lon2) -> new RouteResult(65_000.0), // 65 km in 0.5h = 130 km/h
                defaultConfig
        );
        assertThat(resultAccepted.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);

        // Case B: Straight line = 45 km in 0.5h -> Straight * 1.4 = 63 km -> speed 63 / 0.5 = 126 km/h (> 120).
        // Routed = 65 km in 0.5h -> speed 130 km/h (> 120). BOTH exceed -> REJECTED_SPEED with 0m!
        double deltaLat45km = (45_000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint p3 = TripPoint.fromGps(new GpsPoint(deltaLat45km, 0.0, t0.plusSeconds(1800), 10.0));
        DistanceResult resultRejected = engine.calculate(
                List.of(p1, p3),
                (lat1, lon1, lat2, lon2) -> new RouteResult(65_000.0),
                defaultConfig
        );
        assertThat(resultRejected.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(resultRejected.segments().get(0).meters()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Pre-check: straight-line speed > limit rejects immediately without calling provider")
    void speedPreCheckSavesProviderCall() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));
        // Distance 150 km in 1 hour -> straight speed 150 km/h > 120 km/h
        double deltaLat150km = (150_000.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(deltaLat150km, 0.0, t0.plusSeconds(3600), 10.0));

        AtomicInteger callCount = new AtomicInteger(0);
        DistanceResult result = engine.calculate(
                List.of(p1, p2),
                (lat1, lon1, lat2, lon2) -> {
                    callCount.incrementAndGet();
                    return new RouteResult(160_000.0);
                },
                defaultConfig
        );

        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(result.segments().get(0).meters()).isEqualTo(0L);
        assertThat(callCount.get()).isEqualTo(0); // Provider was never called
        assertThat(result.providerCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("Zero time gap with different points -> REJECTED_SPEED with 0m and no provider call")
    void zeroTimeDifferenceWithDifferentCoordinates() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.91, 77.58, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.95, 77.58, t0, 10.0));

        DistanceResult result = engine.calculate(List.of(p1, p2), null, defaultConfig);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.REJECTED_SPEED);
        assertThat(result.segments().get(0).meters()).isEqualTo(0L);
        assertThat(result.providerCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("Zero time gap with identical points -> 0 metres, SHORT_GAP_AERIAL")
    void zeroTimeDifferenceWithIdenticalCoordinates() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.91, 77.58, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.91, 77.58, t0, 10.0));

        DistanceResult result = engine.calculate(List.of(p1, p2), null, defaultConfig);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
        assertThat(result.segments().get(0).meters()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Provider failure: throws exception -> FALLBACK_AERIAL_1_4 = straight * 1.4 and trip status is DEGRADED")
    void providerThrowsTriggersFallbackAndDegraded() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.9100, 77.5800, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.9300, 77.5900, t0.plusSeconds(300), 10.0));

        double straightMeters = HaversineCalculator.distanceMeters(p1.latitude(), p1.longitude(), p2.latitude(), p2.longitude());
        long expectedMeters = Math.round(straightMeters * 1.4);

        RoutingProvider failingProvider = (fromLat, fromLon, toLat, toLon) -> {
            throw new RoutingUnavailableException("Connection timeout to OSRM engine");
        };

        DistanceResult result = engine.calculate(List.of(p1, p2), failingProvider, defaultConfig);

        assertThat(result.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.FALLBACK_AERIAL_1_4);
        assertThat(result.segments().get(0).meters()).isEqualTo(expectedMeters);
        assertThat(result.totalMeters()).isEqualTo(expectedMeters);
    }

    @Test
    @DisplayName("Provider returns invalid values (NaN, negative, infinite, null) -> triggers FALLBACK_AERIAL_1_4 and DEGRADED")
    void providerInvalidValuesTriggerFallback() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.9100, 77.5800, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.9300, 77.5900, t0.plusSeconds(300), 10.0));

        List<RoutingProvider> invalidProviders = List.of(
                (lat1, lon1, lat2, lon2) -> new RouteResult(Double.NaN),
                (lat1, lon1, lat2, lon2) -> new RouteResult(-100.0),
                (lat1, lon1, lat2, lon2) -> new RouteResult(Double.POSITIVE_INFINITY),
                (lat1, lon1, lat2, lon2) -> null
        );

        for (RoutingProvider provider : invalidProviders) {
            DistanceResult result = engine.calculate(List.of(p1, p2), provider, defaultConfig);
            assertThat(result.status()).isEqualTo(TripStatus.DEGRADED);
            assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.FALLBACK_AERIAL_1_4);
        }
    }

    @Test
    @DisplayName("Rounding: 0.4m rounds to 0, 0.5m rounds to 1, and totalMeters strictly equals sum of segment longs")
    void roundingRulesAndTotalSumIntegrity() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");

        // We create points that produce straight-line distances of approx 0.4m and 0.5m
        // 1m is approx 8.993e-6 degrees lat
        double delta0_4m = (0.4 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        double delta0_5m = (0.5 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);

        TripPoint p0 = TripPoint.fromGps(new GpsPoint(0.0, 0.0, t0, 10.0));
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(delta0_4m, 0.0, t0.plusSeconds(10), 10.0)); // ~0.4m
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(delta0_4m + delta0_5m, 0.0, t0.plusSeconds(20), 10.0)); // ~0.5m

        DistanceResult result = engine.calculate(List.of(p0, p1, p2), null, defaultConfig);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).meters()).isEqualTo(0L); // 0.4m rounds to 0
        assertThat(result.segments().get(1).meters()).isEqualTo(1L); // 0.5m rounds to 1
        assertThat(result.totalMeters()).isEqualTo(1L);
        assertThat(result.totalMeters()).isEqualTo(result.segments().get(0).meters() + result.segments().get(1).meters());
    }

    @Test
    @DisplayName("Case-study scenario: 22-minute gap with two drops produces 3 distinct labelled segments whose sum equals total")
    void caseStudy22MinuteGapScenario() {
        Instant t0 = Instant.parse("2026-10-30T18:00:00Z");

        TripPoint gpsStart = TripPoint.fromGps(new GpsPoint(12.9200, 77.5800, t0, 10.0));
        TripPoint drop1 = TripPoint.fromAnchor(12.9350, 77.5850, t0.plusSeconds(480), AnchorSource.EMPLOYEE_ACTUAL);
        TripPoint drop2 = TripPoint.fromAnchor(12.9500, 77.5900, t0.plusSeconds(960), AnchorSource.EMPLOYEE_ACTUAL);
        TripPoint gpsEnd = TripPoint.fromGps(new GpsPoint(12.9650, 77.5950, t0.plusSeconds(1320), 10.0));

        List<TripPoint> timeline = List.of(gpsStart, drop1, drop2, gpsEnd);

        RoutingProvider mockProvider = (lat1, lon1, lat2, lon2) -> new RouteResult(2400.0);

        DistanceResult result = engine.calculate(timeline, mockProvider, defaultConfig);

        assertThat(result.segments()).hasSize(3);
        assertThat(result.providerCalls()).isEqualTo(3);
        assertThat(result.totalMeters()).isEqualTo(7200L); // 3 * 2400

        long sumOfSegments = 0;
        for (int i = 0; i < result.segments().size(); i++) {
            SegmentResult seg = result.segments().get(i);
            assertThat(seg.sequenceNumber()).isEqualTo(i + 1);
            assertThat(seg.rule()).isEqualTo(SegmentRule.ROUTED);
            assertThat(seg.reason()).isNotBlank();
            sumOfSegments += seg.meters();
        }
        assertThat(result.totalMeters()).isEqualTo(sumOfSegments);
    }

    @Test
    @DisplayName("Bad-fix example: GPS cleaner removes 9 km burst, and DistanceEngine calculates same distance as pristine route")
    void badFixEndToEndPipelineTest() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        GpsPoint pStart = new GpsPoint(12.9200, 77.5800, t0, 10.0);
        GpsPoint pEnd = new GpsPoint(12.9250, 77.5800, t0.plusSeconds(30), 10.0);

        // Pristine route
        List<GpsPoint> pristinePoints = List.of(pStart, pEnd);

        // Corrupted route with 9km burst
        GpsPoint burst1 = new GpsPoint(13.0000, 77.5800, t0.plusSeconds(1), 10.0);
        GpsPoint burst2 = new GpsPoint(13.0000, 77.5800, t0.plusSeconds(4), 10.0);
        List<GpsPoint> noisyPoints = List.of(pStart, burst1, burst2, pEnd);

        GpsCleaner cleaner = new GpsCleaner();
        TripTimelineMerger merger = new TripTimelineMerger();

        CleaningResult cleanPristine = cleaner.clean(pristinePoints, GpsCleaningConfig.defaults());
        CleaningResult cleanNoisy = cleaner.clean(noisyPoints, GpsCleaningConfig.defaults());

        List<TripPoint> timelinePristine = merger.merge(cleanPristine.cleanedPoints(), List.of());
        List<TripPoint> timelineNoisy = merger.merge(cleanNoisy.cleanedPoints(), List.of());

        DistanceResult resultPristine = engine.calculate(timelinePristine, null, defaultConfig);
        DistanceResult resultNoisy = engine.calculate(timelineNoisy, null, defaultConfig);

        // Total distance must be exactly the same!
        assertThat(resultNoisy.totalMeters()).isEqualTo(resultPristine.totalMeters());
        assertThat(resultNoisy.segments().size()).isEqualTo(resultPristine.segments().size());
    }

    @Test
    @DisplayName("Reason strings must never contain coordinate values (latitude or longitude)")
    void reasonStringsNeverContainCoordinates() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = TripPoint.fromGps(new GpsPoint(12.9716, 77.5946, t0, 10.0));
        TripPoint p2 = TripPoint.fromGps(new GpsPoint(12.9750, 77.5990, t0.plusSeconds(30), 10.0));

        DistanceResult result = engine.calculate(List.of(p1, p2), null, defaultConfig);
        String reason = result.segments().get(0).reason();

        assertThat(reason).doesNotContain("12.9716");
        assertThat(reason).doesNotContain("77.5946");
        assertThat(reason).doesNotContain("12.9750");
        assertThat(reason).doesNotContain("77.5990");
    }

    @Test
    @DisplayName("Empty, null, and single-point timelines return empty DistanceResult with 0 metres and CALCULATED status")
    void emptyNullAndSinglePointTimelines() {
        DistanceResult nullResult = engine.calculate(null, null, defaultConfig);
        assertThat(nullResult.segments()).isEmpty();
        assertThat(nullResult.totalMeters()).isEqualTo(0L);
        assertThat(nullResult.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(nullResult.ruleVersion()).isEqualTo(DistanceEngine.RULE_VERSION);

        DistanceResult emptyResult = engine.calculate(List.of(), null, defaultConfig);
        assertThat(emptyResult.segments()).isEmpty();
        assertThat(emptyResult.totalMeters()).isEqualTo(0L);

        DistanceResult singlePointResult = engine.calculate(
                List.of(TripPoint.fromGps(new GpsPoint(12.9, 77.5, Instant.now(), 10.0))),
                null,
                defaultConfig
        );
        assertThat(singlePointResult.segments()).isEmpty();
        assertThat(singlePointResult.totalMeters()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Determinism: executing calculate() repeatedly on identical timelines produces identical output")
    void determinismCheck() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        List<TripPoint> timeline = List.of(
                TripPoint.fromGps(new GpsPoint(12.91, 77.58, t0, 10.0)),
                TripPoint.fromGps(new GpsPoint(12.92, 77.58, t0.plusSeconds(30), 10.0)),
                TripPoint.fromGps(new GpsPoint(12.94, 77.59, t0.plusSeconds(300), 10.0))
        );

        DistanceResult run1 = engine.calculate(timeline, (lat1, lon1, lat2, lon2) -> new RouteResult(2500.0), defaultConfig);
        DistanceResult run2 = engine.calculate(timeline, (lat1, lon1, lat2, lon2) -> new RouteResult(2500.0), defaultConfig);

        assertThat(run1.totalMeters()).isEqualTo(run2.totalMeters());
        assertThat(run1.segments()).isEqualTo(run2.segments());
        assertThat(run1.ruleCounts()).isEqualTo(run2.ruleCounts());
    }

    @Test
    @DisplayName("The input timeline list passed to calculate() must not be modified")
    void inputListIsNotModified() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        List<TripPoint> input = new ArrayList<>(List.of(
                TripPoint.fromGps(new GpsPoint(12.91, 77.58, t0, 10.0)),
                TripPoint.fromGps(new GpsPoint(12.92, 77.58, t0.plusSeconds(30), 10.0))
        ));
        List<TripPoint> inputCopy = List.copyOf(input);

        engine.calculate(input, null, defaultConfig);

        assertThat(input).isEqualTo(inputCopy);
    }
}
