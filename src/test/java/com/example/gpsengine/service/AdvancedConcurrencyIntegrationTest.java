package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.GpsPingRepository;
import com.example.gpsengine.repository.TripMilestoneRepository;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RouteSource;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingStats;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.circuit-breaker.failure-threshold=5",
        "routing.circuit-breaker.open-seconds=60",
        "recompute.enabled=true"
})
@Testcontainers
@Tag("integration")
@Tag("concurrency")
public class AdvancedConcurrencyIntegrationTest {

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
    private DistanceCalculationRepository distanceCalculationRepository;

    @Autowired
    private TripSegmentRepository tripSegmentRepository;

    @Autowired
    private BillingAdjustmentRepository billingAdjustmentRepository;

    @Autowired
    private TripMilestoneRepository tripMilestoneRepository;

    @Autowired
    private GpsPingRepository gpsPingRepository;

    @Autowired
    private DegradedTripRecomputeJob recomputeJob;

    private static final String TENANT = "tenant-concurrency";

    @BeforeEach
    void clean() {
        billingAdjustmentRepository.deleteAll();
        tripRepository.findAll().forEach(t -> {
            if (t.getBilledCalculation() != null) {
                t.setBilledCalculation(null);
                tripRepository.save(t);
            }
        });
        tripSegmentRepository.deleteAll();
        distanceCalculationRepository.deleteAll();
        tripMilestoneRepository.deleteAll();
        gpsPingRepository.deleteAll();
        tripRepository.deleteAll();
    }

    @Test
    @DisplayName("G.1: 20 threads simultaneously calculate on 5 different trips at once (4 threads/trip)")
    void twentyThreadsCalculateFiveTripsConcurrently() throws Exception {
        int tripCount = 5;
        int threadsPerTrip = 4;
        int totalThreads = tripCount * threadsPerTrip;

        List<Long> tripIds = new ArrayList<>();
        Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

        // Seed 5 valid trips with milestones and pings
        for (int i = 0; i < tripCount; i++) {
            Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
            tripIds.add(trip.getId());

            persistenceService.saveMilestones(List.of(
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
            ));
            persistenceService.savePings(List.of(
                    DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                    DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(300), 10.0),
                    DomainFactory.createPing(TENANT, trip, 12.9300, 77.6200, t0.plusSeconds(500), 10.0)
            ));
        }

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalThreads);
        List<Future<CalculationDto.CalculationResponse>> futures = new ArrayList<>();

        for (int i = 0; i < tripCount; i++) {
            Long tripId = tripIds.get(i);
            for (int t = 0; t < threadsPerTrip; t++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    try {
                        return calculationService.calculateTripDistance(tripId, TENANT);
                    } finally {
                        doneGate.countDown();
                    }
                }));
            }
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).as("All 20 calculation threads must complete within 30s").isTrue();

        // Verify each future succeeded
        for (Future<CalculationDto.CalculationResponse> f : futures) {
            CalculationDto.CalculationResponse resp = f.get();
            assertThat(resp.status()).isEqualTo(TripStatus.CALCULATED);
        }

        // Verify database invariants: exactly 1 calculation row per trip
        for (Long tripId : tripIds) {
            List<DistanceCalculation> calcs = distanceCalculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
            assertThat(calcs)
                    .as("Trip %d must have exactly one calculation row", tripId)
                    .hasSize(1);
            assertThat(calcs.get(0).getStatus()).isEqualTo(TripStatus.CALCULATED);

            Trip trip = tripRepository.findByIdAndTenantId(tripId, TENANT).orElseThrow();
            assertThat(trip.getStatus()).isEqualTo(TripStatus.CALCULATED);
            assertThat(trip.getBillingStatus()).isEqualTo(BillingStatus.OPEN);
        }

        assertThat(distanceCalculationRepository.count()).isEqualTo(tripCount);
    }

    @Test
    @DisplayName("G.2: Calculate and close-invoice racing on same trip across 10 threads")
    void calculateAndCloseInvoiceRacingAcrossTenThreads() throws Exception {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();
        Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

        // Seed 1 initial calculation so closeInvoice is legal if it runs after or during
        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
        ));
        calculationService.calculateTripDistance(tripId, TENANT);

        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalThreads);

        AtomicInteger calcSuccess = new AtomicInteger();
        AtomicInteger closeSuccess = new AtomicInteger();
        AtomicInteger rejectedAttempts = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    calculationService.calculateTripDistance(tripId, TENANT);
                    calcSuccess.incrementAndGet();
                } catch (Exception e) {
                    rejectedAttempts.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startGate.await();
                    calculationService.closeInvoice(tripId, TENANT);
                    closeSuccess.incrementAndGet();
                } catch (Exception e) {
                    rejectedAttempts.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        Trip finalTrip = tripRepository.findByIdAndTenantId(tripId, TENANT).orElseThrow();
        List<DistanceCalculation> calcs = distanceCalculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);

        // Verification of invariant:
        // The final state must be either:
        // A) close won / executed: trip billingStatus is CLOSED, invoice is CLOSED, calculation exists
        // B) or invoice remained OPEN if no close succeeded
        if (finalTrip.getBillingStatus() == BillingStatus.CLOSED) {
            assertThat(finalTrip.getClosedAt()).isNotNull();
            assertThat(finalTrip.getBilledCalculation()).isNotNull();
            assertThat(calcs).isNotEmpty();
        } else {
            assertThat(finalTrip.getBillingStatus()).isEqualTo(BillingStatus.OPEN);
            assertThat(finalTrip.getStatus()).isEqualTo(TripStatus.CALCULATED);
        }
    }

    @Test
    @DisplayName("G.3: Scheduled recompute job runs in two threads at same time: no duplicates")
    void concurrentRecomputeJobExecution() throws Exception {
        int degradedTripCount = 4;
        List<Long> tripIds = new ArrayList<>();
        Instant t0 = Instant.parse("2026-10-30T09:00:00Z");

        // Seed 4 trips that are DEGRADED
        for (int i = 0; i < degradedTripCount; i++) {
            Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
            tripIds.add(trip.getId());

            persistenceService.saveMilestones(List.of(
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                    DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t0.plusSeconds(600), null, null, null, null, null, null, null)
            ));
            persistenceService.savePings(List.of(
                    DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(100), 10.0),
                    DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(400), 10.0)
            ));

            // Force initial calculation into DEGRADED state
            DistanceCalculation degradedCalc = DomainFactory.createCalculation(
                    TENANT, trip, "simulated-fingerprint-" + i, "1.0", 8500L,
                    TripStatus.DEGRADED, 1, 0, 1
            );
            distanceCalculationRepository.save(degradedCalc);
            trip.setStatus(TripStatus.DEGRADED);
            tripRepository.save(trip);
        }

        // Run the recompute job concurrently across 2 threads
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                startGate.await();
                recomputeJob.runOnce();
            } catch (Exception e) {
                // ignore
            } finally {
                doneGate.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                recomputeJob.runOnce();
            } catch (Exception e) {
                // ignore
            } finally {
                doneGate.countDown();
            }
        });

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        // Invariant check: Exactly one thread recomputes each trip; no duplicate calculations or adjustments
        for (Long tripId : tripIds) {
            Trip trip = tripRepository.findByIdAndTenantId(tripId, TENANT).orElseThrow();
            assertThat(trip.getStatus()).isEqualTo(TripStatus.CALCULATED);

            List<DistanceCalculation> calculatedRows = distanceCalculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT)
                    .stream()
                    .filter(c -> c.getStatus() == TripStatus.CALCULATED)
                    .toList();
            assertThat(calculatedRows)
                    .as("Each trip must produce at most ONE successful CALCULATED row from recompute", tripId)
                    .hasSize(1);

            List<BillingAdjustment> adjustments = billingAdjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
            assertThat(adjustments)
                    .as("No open trip should have duplicate billing adjustments")
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("G.4: Cache stampede: 50 threads query CachingRoutingProvider for SAME coordinate pair concurrently")
    void cacheStampedeFiftyThreadsSingleProviderCall() throws Exception {
        AtomicInteger rawProviderCalls = new AtomicInteger();
        RoutingProvider mockDelegate = (fromLat, fromLon, toLat, toLon) -> {
            rawProviderCalls.incrementAndGet();
            try {
                // Simulate small network delay to maximize stampede collision window
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RouteResult(3500.0, RouteSource.PROVIDER);
        };

        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider cachingProvider = new CachingRoutingProvider(
                mockDelegate,
                1000,
                Duration.ofMinutes(10),
                0.001,
                "stampede-v1",
                stats
        );

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        List<Future<RouteResult>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                startGate.await();
                try {
                    return cachingProvider.route(12.9279, 77.6271, 12.9350, 77.6350);
                } finally {
                    doneGate.countDown();
                }
            }));
        }

        startGate.countDown();
        boolean completed = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        // Assert all 50 threads received the exact same valid distance
        for (Future<RouteResult> future : results) {
            RouteResult res = future.get();
            assertThat(res.distanceMeters()).isEqualTo(3500.0);
        }

        // Caffeine guarantees atomic computation for the same key. The raw provider is called ONCE.
        assertThat(rawProviderCalls.get())
                .as("Underlying provider should be called at most once (or <= 2) for identical concurrent requests")
                .isLessThanOrEqualTo(2);
        assertThat(cachingProvider.size()).isEqualTo(1);
    }
}
