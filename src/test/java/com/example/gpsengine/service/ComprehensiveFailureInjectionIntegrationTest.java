package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.config.ConfigFingerprintService;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.HaversineCalculator;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.job.RecomputeStats;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.CircuitBreakerRoutingProvider;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.OsrmRoutingProvider;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RouteSource;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingUnavailableException;
import com.example.gpsengine.security.TestSecurityHelper;
import com.example.gpsengine.testutil.TestClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
@Testcontainers
@Tag("integration")
@Tag("failure-injection")
class ComprehensiveFailureInjectionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private CalculationService calculationService;

    @Autowired
    private TripDataPersistenceService persistenceService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripSegmentRepository segmentRepository;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private CachingRoutingProvider cachingRoutingProvider;

    @Autowired
    private RecomputeStats recomputeStats;

    @Autowired
    private TestSecurityHelper testSecurityHelper;

    private static final String TENANT = "tenant-fail-comp";

    @BeforeEach
    void setUp() {
        testSecurityHelper.createOrGetUser("op-fail", TENANT, Role.OPERATOR, null);
        circuitBreaker.reset();
        cachingRoutingProvider.invalidateAll();
    }

    @Test
    @DisplayName("B.1: Provider fails halfway through ONE trip -> DEGRADED, early ROUTED, later FALLBACK_AERIAL_1_4 equal straight*1.4")
    void providerFailsHalfwayThroughOneTrip() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(1800), null, null, null, null, null, null, null)
        ));

        // 4 pings creating 3 routed gaps (each gap > 70s and speed > 5 km/h)
        List<GpsPing> pings = List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9550, 77.6000, t0.plusSeconds(400), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(800), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9300, 77.6200, t0.plusSeconds(1400), 10.0)
        );
        persistenceService.savePings(pings);

        // Fail after 2 successful calls: early calls succeed, 3rd and subsequent fail
        FakeRoutingProvider failingProvider = new FakeRoutingProvider(1.3, 2, 0);
        CalculationService failingCalcService = createCalculationService(failingProvider);

        CalculationDto.CalculationResponse response = failingCalcService.calculateTripDistance(tripId, TENANT);

        assertThat(response.status()).isEqualTo(TripStatus.DEGRADED);

        List<TripSegment> segments = segmentRepository.findByCalculationIdOrderBySequenceNumberAsc(response.id());
        assertThat(segments).isNotEmpty();

        // Count rules
        long routedCount = segments.stream().filter(s -> s.getRule() == SegmentRule.ROUTED).count();
        long fallbackCount = segments.stream().filter(s -> s.getRule() == SegmentRule.FALLBACK_AERIAL_1_4).count();

        assertThat(routedCount).isGreaterThanOrEqualTo(1);
        assertThat(fallbackCount).isGreaterThanOrEqualTo(1);

        // Verify fallback segments equal Math.round(straightMeters * 1.4)
        for (TripSegment s : segments) {
            if (s.getRule() == SegmentRule.FALLBACK_AERIAL_1_4) {
                double straight = HaversineCalculator.distanceMeters(s.getFromLat(), s.getFromLon(), s.getToLat(), s.getToLon());
                long expectedMeters = Math.round(straight * 1.4);
                assertThat(s.getMeters()).isEqualTo(expectedMeters);
            }
        }
    }

    @Test
    @DisplayName("B.2: Provider fails halfway through 10 trips -> first CALCULATED, rest DEGRADED, healing recovers all")
    void providerFailsHalfwayThroughTripSetAndHeals() {
        int tripCount = 10;
        List<Long> tripIds = new ArrayList<>();
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

        for (int i = 0; i < tripCount; i++) {
            Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
            Long tid = trip.getId();
            tripIds.add(tid);

            persistenceService.saveMilestones(List.of(
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
            ));
            persistenceService.savePings(List.of(
                    DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                    DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
            ));
        }

        // Allow 6 provider calls before failing (each trip takes ~2-3 calls, so first ~2-3 trips CALCULATED, rest DEGRADED)
        FakeRoutingProvider failingProvider = new FakeRoutingProvider(1.3, 5, 0);
        CalculationService failingCalcService = createCalculationService(failingProvider);

        for (Long tid : tripIds) {
            failingCalcService.calculateTripDistance(tid, TENANT);
        }

        long calculatedBefore = tripIds.stream().map(tripRepository::findById).map(Optional::get).filter(t -> t.getStatus() == TripStatus.CALCULATED).count();
        long degradedBefore = tripIds.stream().map(tripRepository::findById).map(Optional::get).filter(t -> t.getStatus() == TripStatus.DEGRADED).count();

        assertThat(calculatedBefore).isGreaterThan(0);
        assertThat(degradedBefore).isGreaterThan(0);
        assertThat(calculatedBefore + degradedBefore).isEqualTo(tripCount);

        // Heal provider: reset circuit breaker and run recompute job
        circuitBreaker.reset();
        DegradedTripRecomputeJob job = new DegradedTripRecomputeJob(
                persistenceService,
                calculationService,
                circuitBreaker,
                recomputeStats,
                20
        );

        job.runOnce();

        long degradedAfter = tripIds.stream().map(tripRepository::findById).map(Optional::get).filter(t -> t.getStatus() == TripStatus.DEGRADED).count();
        assertThat(degradedAfter).isEqualTo(0);

        long calculatedAfter = tripIds.stream().map(tripRepository::findById).map(Optional::get).filter(t -> t.getStatus() == TripStatus.CALCULATED).count();
        assertThat(calculatedAfter).isEqualTo(tripCount);
    }

    @Test
    @DisplayName("B.3: Slow provider with request timeout returns 201 DEGRADED within bounded time (<10s) and open breaker accelerates next call")
    void slowProviderBoundedTimeoutAndBreakerAcceleration() throws IOException {
        // Start local JDK HttpServer that sleeps 3500ms (longer than timeout of 1000ms)
        HttpServer slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/route/v1/driving", exchange -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {}
            byte[] resp = "{}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        slowServer.start();

        try {
            int port = slowServer.getAddress().getPort();
            // Connect timeout 500ms, request timeout 1000ms
            OsrmRoutingProvider slowProvider = new OsrmRoutingProvider(
                    "http://127.0.0.1:" + port,
                    500,
                    1000
            );

            // Wrap in circuit breaker with failureThreshold = 1, openSeconds = 30
            TestClock testClock = new TestClock(Instant.now());
            CircuitBreaker customBreaker = new CircuitBreaker(1, Duration.ofSeconds(30), testClock);
            CircuitBreakerRoutingProvider breakerProvider = new CircuitBreakerRoutingProvider(slowProvider, customBreaker, null);
            CalculationService slowCalcService = createCalculationService(breakerProvider);

            Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
            Long tripId = trip.getId();
            Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

            persistenceService.saveMilestones(List.of(
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
            ));
            persistenceService.savePings(List.of(
                    DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                    DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
            ));

            long startCall1 = System.currentTimeMillis();
            CalculationDto.CalculationResponse resp1 = slowCalcService.calculateTripDistance(tripId, TENANT);
            long durationCall1 = System.currentTimeMillis() - startCall1;

            assertThat(resp1.status()).isEqualTo(TripStatus.DEGRADED);
            assertThat(durationCall1).as("Slow call must return within generous 10s timeout").isLessThan(10000);
            assertThat(customBreaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

            // Call 2: Next calculation on a different trip with breaker OPEN should fail immediately (< 200ms)
            Trip trip2 = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
            Long trip2Id = trip2.getId();
            persistenceService.saveMilestones(List.of(
                    DomainFactory.createMilestone(TENANT, trip2, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                    DomainFactory.createMilestone(TENANT, trip2, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
            ));
            persistenceService.savePings(List.of(
                    DomainFactory.createPing(TENANT, trip2, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                    DomainFactory.createPing(TENANT, trip2, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
            ));

            long startCall2 = System.currentTimeMillis();
            CalculationDto.CalculationResponse resp2 = slowCalcService.calculateTripDistance(trip2Id, TENANT);
            long durationCall2 = System.currentTimeMillis() - startCall2;

            assertThat(resp2.status()).isEqualTo(TripStatus.DEGRADED);
            assertThat(durationCall2).as("Call while breaker is OPEN must fast-fail without HTTP delay (<500ms)").isLessThan(500);

        } finally {
            slowServer.stop(0);
        }
    }

    @Test
    @DisplayName("B.4: Provider returns garbage (negative distance / NaN) -> DEGRADED, no exception reaches client")
    void providerReturnsGarbageHandledGracefully() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
        ));

        RoutingProvider garbageProvider = (fromLat, fromLon, toLat, toLon) -> new RouteResult(-999.0, RouteSource.PROVIDER);
        CalculationService garbageService = createCalculationService(garbageProvider);

        CalculationDto.CalculationResponse resp = garbageService.calculateTripDistance(tripId, TENANT);

        assertThat(resp.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(resp.totalMeters()).isGreaterThan(0); // Fallback aerial * 1.4 computed
    }

    @Test
    @DisplayName("B.6: Crash before job finishes: new job instance recovers degraded trip because job holds no essential in-memory state")
    void crashBeforeJobFinishesNewJobInstanceRecoversTrip() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
        ));

        // Make it DEGRADED
        CalculationService failingService = createCalculationService(new FakeRoutingProvider(1.3, 0, 0));
        failingService.calculateTripDistance(tripId, TENANT);

        Trip tripBefore = tripRepository.findById(tripId).orElseThrow();
        assertThat(tripBefore.getStatus()).isEqualTo(TripStatus.DEGRADED);

        // "Crash" the job by discarding the previous job bean and instantiating a completely fresh instance
        circuitBreaker.reset();
        DegradedTripRecomputeJob newJobInstance = new DegradedTripRecomputeJob(
                persistenceService,
                calculationService,
                circuitBreaker,
                recomputeStats,
                10
        );

        newJobInstance.runOnce();

        Trip tripAfter = tripRepository.findById(tripId).orElseThrow();
        assertThat(tripAfter.getStatus()).isEqualTo(TripStatus.CALCULATED);
    }

    @Test
    @DisplayName("B.8: Cache poisoning check: failed provider call and fallback are never served from cache after recovery")
    void cachePoisoningPreventionOnFailure() {
        cachingRoutingProvider.invalidateAll();
        circuitBreaker.reset();

        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
        ));

        // 1. Provider fails during initial calculation -> DEGRADED
        FakeRoutingProvider failRaw = new FakeRoutingProvider(1.3);
        failRaw.setFailing(true);
        CalculationService failingService = createCalculationService(failRaw);
        failingService.calculateTripDistance(tripId, TENANT);

        // 2. Clear breaker and verify caching provider cache size is 0 (failed calls are not cached)
        circuitBreaker.reset();
        assertThat(cachingRoutingProvider.size()).isEqualTo(0);

        // 3. Provider recovers and calculates trip with standard routingProvider
        CalculationDto.CalculationResponse recoveredCalc = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(recoveredCalc.status()).isEqualTo(TripStatus.CALCULATED);
        // The first successful run must be a cache miss that queries the provider, NOT a cached fallback!
        assertThat(recoveredCalc.routingProviderCalls()).isGreaterThan(0);
        assertThat(recoveredCalc.cacheHits()).isEqualTo(0);
    }

    @Test
    @DisplayName("B.9: Breaker flapping: alternating fail/success transitions as specified without losing calls")
    void breakerFlappingTransitions() throws Exception {
        TestClock testClock = new TestClock(Instant.now());
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(10), testClock); // 3 failures, 10s open

        AtomicInteger callCount = new AtomicInteger();
        RoutingProvider flapper = (lat1, lon1, lat2, lon2) -> {
            int c = callCount.incrementAndGet();
            if (c % 2 == 1) {
                throw new RoutingUnavailableException("Flapping failure on call " + c);
            }
            return new RouteResult(1000.0, RouteSource.PROVIDER);
        };

        CircuitBreakerRoutingProvider cbProvider = new CircuitBreakerRoutingProvider(flapper, cb, null);

        // Call 1: fails (1 failure) -> state CLOSED
        assertThatThrownBy(() -> cbProvider.route(12.0, 77.0, 12.1, 77.1)).isInstanceOf(RoutingUnavailableException.class);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Call 2: succeeds -> failure counter resets to 0 -> state CLOSED
        RouteResult r2 = cbProvider.route(12.0, 77.0, 12.1, 77.1);
        assertThat(r2.distanceMeters()).isEqualTo(1000.0);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 3 consecutive failures to trip breaker
        RoutingProvider alwaysFails = (lat1, lon1, lat2, lon2) -> {
            throw new RoutingUnavailableException("Hard failure");
        };
        CircuitBreakerRoutingProvider hardFailProvider = new CircuitBreakerRoutingProvider(alwaysFails, cb, null);

        assertThatThrownBy(() -> hardFailProvider.route(12.0, 77.0, 12.1, 77.1)).isInstanceOf(RoutingUnavailableException.class);
        assertThatThrownBy(() -> hardFailProvider.route(12.0, 77.0, 12.1, 77.1)).isInstanceOf(RoutingUnavailableException.class);
        assertThatThrownBy(() -> hardFailProvider.route(12.0, 77.0, 12.1, 77.1)).isInstanceOf(RoutingUnavailableException.class);

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Advance clock past open duration (10s) -> HALF_OPEN
        testClock.advanceSeconds(11);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Successful probe in HALF_OPEN recovers breaker to CLOSED
        RoutingProvider goodProvider = (lat1, lon1, lat2, lon2) -> new RouteResult(1000.0, RouteSource.PROVIDER);
        CircuitBreakerRoutingProvider goodBreakerProvider = new CircuitBreakerRoutingProvider(goodProvider, cb, null);
        RouteResult rRecover = goodBreakerProvider.route(12.0, 77.0, 12.1, 77.1);
        assertThat(rRecover.distanceMeters()).isEqualTo(1000.0);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private CalculationService createCalculationService(RoutingProvider provider) {
        return new CalculationService(
                persistenceService,
                provider,
                new ConfigFingerprintService(
                        GpsCleaningConfig.defaults(),
                        AnchorConfig.defaults(),
                        SegmentConfig.defaults()
                ),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }
}

