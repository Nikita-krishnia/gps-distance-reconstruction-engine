package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.job.RecomputeStats;
import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.FakeRoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
@Testcontainers
class FailureInjectionIntegrationTest {

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
    private DistanceCalculationRepository calculationRepository;

    @Autowired
    private BillingAdjustmentRepository adjustmentRepository;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private CachingRoutingProvider cachingRoutingProvider;

    @Autowired
    private RecomputeStats recomputeStats;

    @SpyBean
    private TripSegmentRepository tripSegmentRepository;

    private static final String TENANT = "tenant-fail-test";

    @Test
    @DisplayName("Failure Injection 1: Interrupted write triggers full rollback with trip status unchanged, and subsequent retry succeeds")
    void interruptedWriteRollsBackTransaction() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        trip = tripRepository.save(trip);
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null);

        persistenceService.savePings(List.of(p1));
        persistenceService.saveMilestones(List.of(start, end));

        // Inject failure during segment persistence
        doThrow(new DataIntegrityViolationException("Simulated disk/constraint write failure"))
                .when(tripSegmentRepository).saveAll(any());

        assertThatThrownBy(() -> calculationService.calculateTripDistance(tripId, TENANT))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify full rollback: no DistanceCalculation row saved, trip status remains NEW
        List<DistanceCalculation> calcsAfterFailure = calculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(calcsAfterFailure).isEmpty();

        Trip tripAfterFailure = tripRepository.findById(tripId).orElseThrow();
        assertThat(tripAfterFailure.getStatus()).isEqualTo(TripStatus.NEW);

        // Reset spy and retry
        reset(tripSegmentRepository);

        CalculationDto.CalculationResponse retryResponse = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(retryResponse.status()).isEqualTo(TripStatus.CALCULATED);

        List<DistanceCalculation> calcsAfterRetry = calculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(calcsAfterRetry).hasSize(1);
    }

    @Test
    @DisplayName("Failure Injection 2: Provider fails -> DEGRADED trip without adjustment on closed trip; provider heals -> job recovers trip to CALCULATED with adjustment")
    void providerFailureAndHealingRecomputation() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        trip = tripRepository.save(trip);
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(300);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null);

        persistenceService.savePings(List.of(p1));
        persistenceService.saveMilestones(List.of(start, end));

        // Initial normal calculation and invoice closure
        CalculationDto.CalculationResponse initialCalc = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(initialCalc.status()).isEqualTo(TripStatus.CALCULATED);

        TripDto.CloseInvoiceResponse closeRes = calculationService.closeInvoice(tripId, TENANT);
        assertThat(closeRes.billedMeters()).isEqualTo(initialCalc.totalMeters());

        // Upload late valid ping
        GpsPing latePing = DomainFactory.createPing(TENANT, trip, 12.9500, 77.6000, t0.plusSeconds(150), 10.0);
        persistenceService.savePings(List.of(latePing));

        // Clear cache and simulate provider failure
        cachingRoutingProvider.invalidateAll();
        FakeRoutingProvider fakeProvider = new FakeRoutingProvider(1.3, 0, 0); // fail every call
        CalculationService failingCalcService = new CalculationService(
                persistenceService,
                fakeProvider,
                new com.example.gpsengine.config.ConfigFingerprintService(
                        com.example.gpsengine.engine.GpsCleaningConfig.defaults(),
                        com.example.gpsengine.engine.AnchorConfig.defaults(),
                        com.example.gpsengine.engine.SegmentConfig.defaults()
                ),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );

        // Recalculating closed trip while provider is down -> DEGRADED
        CalculationDto.CalculationResponse degradedCalc = failingCalcService.calculateTripDistance(tripId, TENANT);
        assertThat(degradedCalc.status()).isEqualTo(TripStatus.DEGRADED);

        // IMPORTANT: No billing adjustment must be generated for DEGRADED calculation on a closed trip!
        assertThat(degradedCalc.adjustment()).isNull();
        List<BillingAdjustment> adjustmentsBeforeHeal = adjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(adjustmentsBeforeHeal).isEmpty();

        // Now provider heals: run DegradedTripRecomputeJob
        circuitBreaker.reset();
        DegradedTripRecomputeJob recomputeJob = new DegradedTripRecomputeJob(
                persistenceService,
                calculationService,
                circuitBreaker,
                recomputeStats,
                10
        );

        recomputeJob.runOnce();

        // Verify trip was recovered to CALCULATED
        Trip recoveredTrip = tripRepository.findById(tripId).orElseThrow();
        assertThat(recoveredTrip.getStatus()).isEqualTo(TripStatus.CALCULATED);

        // A correct billing adjustment is now created
        List<BillingAdjustment> adjustmentsAfterHeal = adjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(adjustmentsAfterHeal).hasSize(1);
        assertThat(adjustmentsAfterHeal.get(0).getDeltaMeters()).isNotZero();
    }
}

