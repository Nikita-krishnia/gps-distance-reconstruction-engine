package com.example.gpsengine.routing;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.DistanceEngine;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.PointOrigin;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.TripPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedModeAndRecoveryTest {

    private final DistanceEngine engine = new DistanceEngine();

    @Test
    @DisplayName("Provider fails -> breaker opens -> engine marks FALLBACK_AERIAL_1_4 and DEGRADED")
    void degradedModeOnProviderFailure() {
        CircuitBreakerTest.MutableClock clock = new CircuitBreakerTest.MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);
        RoutingStats stats = new RoutingStats();
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        raw.setFailing(true);

        CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(raw, breaker, stats);
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                breakerProvider, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9800, 77.5946, t0.plusSeconds(120), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2);

        // Trip 1 fails directly
        DistanceResult res1 = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(res1.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(res1.segments().get(0).rule()).isEqualTo(SegmentRule.FALLBACK_AERIAL_1_4);

        // Trip 2 fails directly (consecutive failures = 2 -> breaker OPEN)
        DistanceResult res2 = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(res2.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(breaker.isOpen()).isTrue();

        // Trip 3 rejected by OPEN circuit breaker (fast-fail)
        DistanceResult res3 = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(res3.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(res3.segments().get(0).rule()).isEqualTo(SegmentRule.FALLBACK_AERIAL_1_4);
        // Provider calls should not increment on breaker rejection
        assertThat(res3.providerCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("Cached routes still return ROUTED while breaker is open (availability preservation)")
    void cachedRoutesWorkWhileBreakerOpen() throws RoutingUnavailableException {
        CircuitBreakerTest.MutableClock clock = new CircuitBreakerTest.MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        RoutingStats stats = new RoutingStats();
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);

        CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(raw, breaker, stats);
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                breakerProvider, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9800, 77.5946, t0.plusSeconds(120), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2);

        // Prime cache with successful trip
        DistanceResult primed = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(primed.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(primed.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);

        // Now break provider and trip circuit breaker with an uncached route
        raw.setFailing(true);
        try {
            cachingProvider.route(13.0, 78.0, 13.1, 78.1);
        } catch (RoutingUnavailableException ignored) {}
        assertThat(breaker.isOpen()).isTrue();

        // Query the cached timeline again: cache hit serves the request; status remains CALCULATED
        DistanceResult fromCache = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(fromCache.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(fromCache.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(fromCache.cacheHits()).isEqualTo(1);
        assertThat(fromCache.providerCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("Clock advance + healed provider recovers trip to CALCULATED with identical km")
    void recoveryAfterBreakerHeals() {
        CircuitBreakerTest.MutableClock clock = new CircuitBreakerTest.MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);

        CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(raw, breaker, null);
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                breakerProvider, 1000, Duration.ofHours(1), 0.001, "v1", null
        );

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9800, 77.5946, t0.plusSeconds(120), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2);

        // Baseline: what a healthy provider would calculate
        DistanceResult baseline = engine.calculate(timeline, raw, SegmentConfig.defaults());

        // Trip breaker with failure
        raw.setFailing(true);
        DistanceResult degraded = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(degraded.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(breaker.isOpen()).isTrue();

        // Advance clock past open duration (35s) and heal provider
        clock.advance(Duration.ofSeconds(35));
        raw.setFailing(false);

        // Recompute trip with cachingProvider: half-open probe succeeds and circuit transitions to CLOSED
        DistanceResult recovered = engine.calculate(timeline, cachingProvider, SegmentConfig.defaults());
        assertThat(recovered.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Recompute with fresh clean cache gives exact same km
        CachingRoutingProvider freshCache = new CachingRoutingProvider(
                new CircuitBreakerRoutingProvider(raw, breaker, null),
                1000, Duration.ofHours(1), 0.001, "v1", null
        );
        DistanceResult rerun = engine.calculate(timeline, freshCache, SegmentConfig.defaults());
        assertThat(rerun.totalMeters()).isEqualTo(recovered.totalMeters());
    }

    @Test
    @DisplayName("Mid-run failure: failAfterCalls produces CALCULATED for first trips and DEGRADED for later without crashing")
    void midRunFailureHandling() {
        CircuitBreakerTest.MutableClock clock = new CircuitBreakerTest.MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        // Provider fails after 2 calls
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3, 2, 0L);
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);
        CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(raw, breaker, null);
        // Disable cache so every call hits provider
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                breakerProvider, 1000, Duration.ofHours(1), 0.001, "v1", null
        );

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

        // 5 distinct trips
        for (int i = 0; i < 5; i++) {
            double offset = i * 0.05;
            TripPoint p1 = new TripPoint(12.0 + offset, 77.0, t0, PointOrigin.GPS, null);
            TripPoint p2 = new TripPoint(12.01 + offset, 77.01, t0.plusSeconds(120), PointOrigin.GPS, null);

            DistanceResult res = engine.calculate(List.of(p1, p2), cachingProvider, SegmentConfig.defaults());
            if (i < 2) {
                assertThat(res.status()).isEqualTo(TripStatus.CALCULATED);
            } else {
                assertThat(res.status()).isEqualTo(TripStatus.DEGRADED);
            }
        }
    }
}

