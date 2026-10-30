package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-drift guard (golden test).
 *
 * <p>CRITICAL INVARIANT:
 * If this test fails, you changed the business rules or calculation mechanics!
 * You MUST intentionally bump RULE_VERSION and update the expected values here deliberately.
 */
@Tag("unit")
class RuleDriftGuardGoldenTest {

    @Test
    @DisplayName("Golden test: fixed timeline produces exact expected segments, rules, reasons and total metres")
    void goldenRuleVerification() {
        DistanceEngine engine = new DistanceEngine();
        RoutingProvider fixedProvider = (fromLat, fromLon, toLat, toLon) -> new RouteResult(1500.0);

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9725, 77.5955, t0.plusSeconds(30), PointOrigin.GPS, null); // 140m / 30s -> SHORT_GAP_AERIAL
        TripPoint p3 = new TripPoint(12.9726, 77.5956, t0.plusSeconds(120), PointOrigin.GPS, null); // 15m / 90s -> PARKED
        TripPoint p4 = new TripPoint(12.9600, 77.6100, t0.plusSeconds(300), PointOrigin.GPS, null); // long gap -> ROUTED (1500m)
        List<TripPoint> timeline = List.of(p1, p2, p3, p4);

        DistanceResult result = engine.calculate(timeline, fixedProvider, SegmentConfig.defaults());

        assertThat(result.ruleVersion()).isEqualTo("2026.1");
        assertThat(result.segments()).hasSize(3);

        // Segment 1: SHORT_GAP_AERIAL
        SegmentResult s1 = result.segments().get(0);
        assertThat(s1.sequenceNumber()).isEqualTo(1);
        assertThat(s1.rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
        assertThat(s1.meters()).isEqualTo(140L);

        // Segment 2: PARKED
        SegmentResult s2 = result.segments().get(1);
        assertThat(s2.sequenceNumber()).isEqualTo(2);
        assertThat(s2.rule()).isEqualTo(SegmentRule.PARKED);
        assertThat(s2.meters()).isEqualTo(16L);

        // Segment 3: ROUTED
        SegmentResult s3 = result.segments().get(2);
        assertThat(s3.sequenceNumber()).isEqualTo(3);
        assertThat(s3.rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(s3.meters()).isEqualTo(1500L);

        // Total
        assertThat(result.totalMeters()).isEqualTo(1656L);
    }

    @Test
    @DisplayName("Determinism guard: running the pipeline 100 times gives identical segments and total")
    void determinism100Runs() {
        DistanceEngine engine = new DistanceEngine();
        RoutingProvider fixedProvider = (fromLat, fromLon, toLat, toLon) -> new RouteResult(2500.0);

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9725, 77.5955, t0.plusSeconds(30), PointOrigin.GPS, null);
        TripPoint p3 = new TripPoint(12.9600, 77.6100, t0.plusSeconds(300), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2, p3);

        DistanceResult first = engine.calculate(timeline, fixedProvider, SegmentConfig.defaults());

        for (int i = 0; i < 100; i++) {
            DistanceResult current = engine.calculate(timeline, fixedProvider, SegmentConfig.defaults());
            assertThat(current.totalMeters()).isEqualTo(first.totalMeters());
            assertThat(current.segments()).hasSize(first.segments().size());
            for (int j = 0; j < first.segments().size(); j++) {
                assertThat(current.segments().get(j).meters()).isEqualTo(first.segments().get(j).meters());
                assertThat(current.segments().get(j).rule()).isEqualTo(first.segments().get(j).rule());
            }
        }
    }
}

