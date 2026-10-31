package com.example.gpsengine.monitoring;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.CleaningResult;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.job.RecomputeStats;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.RoutingStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class GpsMetricsTest {

    private MeterRegistry registry;
    private TripRepository tripRepository;
    private RecomputeStats recomputeStats;
    private RoutingStats routingStats;
    private CircuitBreaker circuitBreaker;
    private TestClock clock;
    private GpsMetrics metrics;

    private static class TestClock extends Clock {
        private Instant now;

        public TestClock(Instant initial) {
            this.now = initial;
        }

        public void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tripRepository = mock(TripRepository.class);
        clock = new TestClock(Instant.parse("2026-10-30T10:00:00Z"));
        recomputeStats = new RecomputeStats();
        routingStats = new RoutingStats();
        circuitBreaker = new CircuitBreaker(3, Duration.ofSeconds(30), clock);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rawRoutingProvider", new FakeRoutingProvider());

        metrics = new GpsMetrics(
                registry,
                tripRepository,
                clock,
                recomputeStats,
                routingStats,
                circuitBreaker,
                beanFactory.getBeanProvider(com.example.gpsengine.routing.RoutingProvider.class)
        );
    }

    @Test
    @DisplayName("Calculation metrics record status, duration, rule segments, and pings removed")
    void calculationMetricsRecordProperly() {
        metrics.recordCalculation("calculated", Duration.ofMillis(120));
        metrics.recordCalculation("calculated", Duration.ofMillis(80));
        metrics.recordCalculation("degraded", Duration.ofMillis(50));
        metrics.recordCalculation("reused", Duration.ofMillis(10));
        metrics.recordCalculationFailed();

        assertThat(registry.get("gps.calculations").tag("status", "calculated").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("gps.calculations").tag("status", "degraded").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.calculations").tag("status", "reused").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.calculations").tag("status", "failed").counter().count()).isEqualTo(1.0);

        Timer timer = registry.get("gps.calculation.duration").timer();
        assertThat(timer.count()).isEqualTo(4L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(260.0);

        // Segment rules
        metrics.recordSegments(Map.of(
                SegmentRule.ROUTED, 5,
                SegmentRule.SHORT_GAP_AERIAL, 2,
                SegmentRule.FALLBACK_AERIAL_1_4, 1
        ));

        assertThat(registry.get("gps.calculation.segments").tag("rule", "ROUTED").counter().count()).isEqualTo(5.0);
        assertThat(registry.get("gps.calculation.segments").tag("rule", "SHORT_GAP_AERIAL").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("gps.calculation.segments").tag("rule", "FALLBACK_AERIAL_1_4").counter().count()).isEqualTo(1.0);

        // Pings removed
        CleaningResult cleaningResult = new CleaningResult(
                List.of(new GpsPoint(12.0, 77.0, Instant.now(), 10.0)),
                2, 3, 1, 4
        );
        metrics.recordPingsRemoved(cleaningResult);

        assertThat(registry.get("gps.calculation.pings.removed").tag("reason", "invalid").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("gps.calculation.pings.removed").tag("reason", "duplicate").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("gps.calculation.pings.removed").tag("reason", "low_accuracy").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.calculation.pings.removed").tag("reason", "teleport").counter().count()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Routing stats, breaker state gauge, and transition counters reflect accurately")
    void routingAndBreakerMetrics() {
        routingStats.recordProviderCall();
        routingStats.recordProviderCall();
        routingStats.recordProviderFailure();
        routingStats.recordHit();
        routingStats.recordMiss();
        routingStats.recordBypass();
        routingStats.recordCircuitOpenRejection();

        assertThat(registry.get("gps.routing.provider.calls").functionCounter().count()).isEqualTo(2.0);
        assertThat(registry.get("gps.routing.provider.failures").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.routing.cache").tag("result", "hit").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.routing.cache").tag("result", "miss").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.routing.cache").tag("result", "bypass").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.routing.breaker.rejections").functionCounter().count()).isEqualTo(1.0);

        // Initial breaker state CLOSED (0)
        Gauge breakerGauge = registry.get("gps.routing.breaker.state").gauge();
        assertThat(breakerGauge.value()).isEqualTo(0.0);

        // Trip the breaker (threshold = 3 failures)
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new com.example.gpsengine.routing.RoutingUnavailableException("Simulated");
                }, routingStats);
            } catch (Exception ignored) {}
        }

        // State OPEN (2)
        assertThat(breakerGauge.value()).isEqualTo(2.0);

        // Transition counter CLOSED -> OPEN incremented
        Counter transitionCounter = registry.get("gps.routing.breaker.transitions")
                .tag("from", "closed")
                .tag("to", "open")
                .counter();
        assertThat(transitionCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Recompute job metrics bind to stats and calculate last success age")
    void recomputeJobMetrics() {
        recomputeStats.recordRun();
        recomputeStats.recordAttempt();
        recomputeStats.recordRecovered();
        recomputeStats.recordStillDegraded();
        recomputeStats.recordSkippedRun();
        recomputeStats.recordSuccessRun(clock.instant().minusSeconds(12));

        assertThat(registry.get("gps.recompute.runs").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.recompute.attempted").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.recompute.recovered").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.recompute.still_degraded").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.recompute.skipped").functionCounter().count()).isEqualTo(1.0);

        Gauge lastSuccessGauge = registry.get("gps.recompute.last.success.age.seconds").gauge();
        assertThat(lastSuccessGauge.value()).isBetween(11.9, 12.1);
    }

    @Test
    @DisplayName("Degraded trips gauge snapshot is cached and hits repository at most once per 15s")
    void degradedTripsGaugeCaching() {
        when(tripRepository.countByStatus(TripStatus.DEGRADED)).thenReturn(5L);
        when(tripRepository.findOldestCreatedAtByStatus(TripStatus.DEGRADED))
                .thenReturn(Optional.of(clock.instant().minusSeconds(45)));

        Gauge countGauge = registry.get("gps.degraded.trips").gauge();
        Gauge ageGauge = registry.get("gps.degraded.oldest.age.seconds").gauge();

        // First read triggers database query
        assertThat(countGauge.value()).isEqualTo(5.0);
        assertThat(ageGauge.value()).isBetween(44.9, 45.1);
        assertThat(metrics.getDbSnapshotQueryCount()).isEqualTo(1L);

        // Multiple immediate reads within 15s window MUST NOT hit repository again
        for (int i = 0; i < 10; i++) {
            assertThat(countGauge.value()).isEqualTo(5.0);
            assertThat(ageGauge.value()).isBetween(44.9, 45.1);
        }
        assertThat(metrics.getDbSnapshotQueryCount()).isEqualTo(1L);
        verify(tripRepository, times(1)).countByStatus(TripStatus.DEGRADED);

        // Advance clock by 16 seconds (exceeding 15s refresh window)
        clock.advance(Duration.ofSeconds(16));
        when(tripRepository.countByStatus(TripStatus.DEGRADED)).thenReturn(3L);

        assertThat(countGauge.value()).isEqualTo(3.0);
        assertThat(metrics.getDbSnapshotQueryCount()).isEqualTo(2L);
        verify(tripRepository, times(2)).countByStatus(TripStatus.DEGRADED);
    }

    @Test
    @DisplayName("Billing, concurrency, security, and unexpected error metrics record correctly")
    void billingConcurrencyAndSecurityMetrics() {
        metrics.recordBillingAdjustment(1500L);
        metrics.recordBillingAdjustment(2500L);
        metrics.recordInvoiceClosed();
        metrics.recordConcurrencyConflict();

        assertThat(registry.get("gps.billing.adjustments").counter().count()).isEqualTo(2.0);
        DistributionSummary summary = registry.get("gps.billing.adjustments.delta.abs.meters").summary();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(4000.0);

        assertThat(registry.get("gps.invoices.closed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.concurrency.conflicts").counter().count()).isEqualTo(1.0);

        // Security
        metrics.recordAuthLogin(true);
        metrics.recordAuthLogin(false);
        metrics.recordAuthDenied("401");
        metrics.recordAuthDenied("403");

        assertThat(registry.get("gps.auth.logins").tag("result", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.auth.logins").tag("result", "failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.auth.denied").tag("type", "401").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gps.auth.denied").tag("type", "403").counter().count()).isEqualTo(1.0);

        // Unexpected errors
        metrics.recordUnexpectedError();
        assertThat(registry.get("gps.errors.unexpected").counter().count()).isEqualTo(1.0);
    }
}
