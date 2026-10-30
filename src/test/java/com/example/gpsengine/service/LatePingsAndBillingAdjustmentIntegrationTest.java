package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.job.RecomputeStats;
import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.security.TestSecurityHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class LatePingsAndBillingAdjustmentIntegrationTest {

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
    private TripSegmentRepository segmentRepository;

    @Autowired
    private BillingAdjustmentRepository adjustmentRepository;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private RecomputeStats recomputeStats;

    @Autowired
    private TestSecurityHelper testSecurityHelper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TENANT = "tenant-late-test";
    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = testSecurityHelper.generateToken("admin-late", TENANT, Role.ADMIN, null);
    }

    @Test
    @DisplayName("F.1: Repeat calculate 10 times on unchanged data produces exactly 1 stored row")
    void repeatCalculate10TimesStoresSingleRow() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9500, 77.6100, t0.plusSeconds(900), 10.0)
        ));

        // 1st calculation: creates new row
        CalculationDto.CalculationResponse first = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(first.reused()).isFalse();

        // 9 subsequent calls: all return reused: true
        for (int i = 0; i < 9; i++) {
            CalculationDto.CalculationResponse rep = calculationService.calculateTripDistance(tripId, TENANT);
            assertThat(rep.reused()).isTrue();
            assertThat(rep.totalMeters()).isEqualTo(first.totalMeters());
            assertThat(rep.id()).isEqualTo(first.id());
        }

        List<DistanceCalculation> rows = calculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("F.2: Late pings before closing, then close, then late pings twice -> two adjustments, sum matches delta, billed calculation unchanged")
    void latePingsAndAdjustmentIntegrity() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(3600);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9500, 77.6100, t0.plusSeconds(1800), 10.0)
        ));

        // 1. Initial calculation & late ping before close
        CalculationDto.CalculationResponse calc1 = calculationService.calculateTripDistance(tripId, TENANT);
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9600, 77.6020, t0.plusSeconds(900), 10.0)
        ));
        CalculationDto.CalculationResponse calcBeforeClose = calculationService.calculateTripDistance(tripId, TENANT);

        // 2. Close invoice (locks calcBeforeClose as billed calculation)
        TripDto.CloseInvoiceResponse closeRes = calculationService.closeInvoice(tripId, TENANT);
        Long billedCalcId = closeRes.billedCalculationId();
        long billedMeters = closeRes.billedMeters();
        assertThat(billedMeters).isEqualTo(calcBeforeClose.totalMeters());

        // Snapshot billed calculation and its segments
        DistanceCalculation billedCalcSnapshot = calculationRepository.findById(billedCalcId).orElseThrow();
        List<TripSegment> billedSegmentsSnapshot = segmentRepository.findByCalculationIdOrderBySequenceNumberAsc(billedCalcId);

        // 3. Late ping 1 after closing -> Adjustment 1
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6180, t0.plusSeconds(2400), 10.0)
        ));
        CalculationDto.CalculationResponse calcAfterClose1 = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calcAfterClose1.adjustment()).isNotNull();

        // 4. Late ping 2 after closing -> Adjustment 2
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9350, 77.6220, t0.plusSeconds(3000), 10.0)
        ));
        CalculationDto.CalculationResponse calcAfterClose2 = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calcAfterClose2.adjustment()).isNotNull();

        // Verify two adjustments exist in ledger
        List<BillingAdjustment> adjustments = adjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(adjustments).hasSize(2);

        // Verify sum of deltas equals latestTotal - billedTotal
        long sumDeltas = adjustments.stream().mapToLong(BillingAdjustment::getDeltaMeters).sum();
        assertThat(sumDeltas).isEqualTo(calcAfterClose2.totalMeters() - billedMeters);

        // Verify billed calculation and segments were NOT mutated
        DistanceCalculation billedCalcCurrent = calculationRepository.findById(billedCalcId).orElseThrow();
        List<TripSegment> billedSegmentsCurrent = segmentRepository.findByCalculationIdOrderBySequenceNumberAsc(billedCalcId);

        assertThat(billedCalcCurrent.getTotalMeters()).isEqualTo(billedCalcSnapshot.getTotalMeters());
        assertThat(billedCalcCurrent.getStatus()).isEqualTo(billedCalcSnapshot.getStatus());
        assertThat(billedSegmentsCurrent).hasSameSizeAs(billedSegmentsSnapshot);
        for (int i = 0; i < billedSegmentsCurrent.size(); i++) {
            assertThat(billedSegmentsCurrent.get(i).getMeters()).isEqualTo(billedSegmentsSnapshot.get(i).getMeters());
            assertThat(billedSegmentsCurrent.get(i).getRule()).isEqualTo(billedSegmentsSnapshot.get(i).getRule());
        }
    }

    @Test
    @DisplayName("F.3: Late ping that adds only duplicate or low-accuracy noise produces no new calculation and no adjustment")
    void lateDuplicateOrCoarsePingProducesNoAdjustment() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));
        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0);
        persistenceService.savePings(List.of(p1));

        CalculationDto.CalculationResponse initial = calculationService.calculateTripDistance(tripId, TENANT);
        calculationService.closeInvoice(tripId, TENANT);

        // Upload exact duplicate ping
        GpsPing duplicatePing = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0);
        // Upload low accuracy ping (65m > 50m threshold)
        GpsPing lowAccuracyPing = DomainFactory.createPing(TENANT, trip, 12.9600, 77.6000, t0.plusSeconds(600), 65.0);
        persistenceService.savePings(List.of(duplicatePing, lowAccuracyPing));

        // Recalculate: GpsCleaner drops both, fingerprint is unchanged -> reused = true, no new calculation, no adjustment
        CalculationDto.CalculationResponse afterNoise = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(afterNoise.reused()).isTrue();
        assertThat(afterNoise.adjustment()).isNull();

        List<BillingAdjustment> adjustments = adjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(adjustments).isEmpty();
    }

    @Test
    @DisplayName("F.4: Negative delta case: late ping creates a more direct route resulting in negative deltaKm")
    void negativeDeltaAdjustmentCase() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(3600);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));

        // Initial route: detour ping far east
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9500, 77.7000, t0.plusSeconds(1800), 10.0)
        ));

        CalculationDto.CalculationResponse calcInitial = calculationService.calculateTripDistance(tripId, TENANT);
        calculationService.closeInvoice(tripId, TENANT);
        long initialMeters = calcInitial.totalMeters();

        FakeRoutingProvider customFake = new FakeRoutingProvider(1.0); // 1.0 detour factor instead of 1.3
        CalculationService customService = new CalculationService(
                persistenceService,
                customFake,
                new com.example.gpsengine.config.ConfigFingerprintService(
                        com.example.gpsengine.engine.GpsCleaningConfig.defaults(),
                        com.example.gpsengine.engine.AnchorConfig.defaults(),
                        com.example.gpsengine.engine.SegmentConfig.defaults()
                ),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );

        // Adding a ping and calculating with 1.0 gives a shorter route -> negative delta!
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6100, t0.plusSeconds(2500), 10.0)
        ));
        CalculationDto.CalculationResponse shorterCalc = customService.calculateTripDistance(tripId, TENANT);

        assertThat(shorterCalc.totalMeters()).isLessThan(initialMeters);
        assertThat(shorterCalc.adjustment()).isNotNull();
        assertThat(shorterCalc.adjustment().deltaMeters()).isLessThan(0);
        assertThat(shorterCalc.adjustment().deltaKm()).isLessThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("F.5 & F.6: Recompute job after provider recovery on CLOSED trip creates adjustment exactly once and returns formatted JSON numbers")
    void recomputeOnClosedTripCreatesSingleAdjustmentWithFormattedJson() throws Exception {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0.plusSeconds(300), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9500, 77.6100, t0.plusSeconds(900), 10.0)
        ));

        // Initial calculation and close
        calculationService.calculateTripDistance(tripId, TENANT);
        calculationService.closeInvoice(tripId, TENANT);

        // Upload late ping
        persistenceService.savePings(List.of(
                DomainFactory.createPing(TENANT, trip, 12.9400, 77.6200, t0.plusSeconds(1200), 10.0)
        ));

        // Simulate provider down: trip recalculated to DEGRADED on closed trip
        CalculationService failingService = new CalculationService(
                persistenceService,
                new FakeRoutingProvider(1.3, 0, 0), // fail every call
                new com.example.gpsengine.config.ConfigFingerprintService(
                        com.example.gpsengine.engine.GpsCleaningConfig.defaults(),
                        com.example.gpsengine.engine.AnchorConfig.defaults(),
                        com.example.gpsengine.engine.SegmentConfig.defaults()
                ),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );
        CalculationDto.CalculationResponse degradedRes = failingService.calculateTripDistance(tripId, TENANT);
        assertThat(degradedRes.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(degradedRes.adjustment()).isNull(); // No adjustment while DEGRADED

        // Now provider heals: run job twice
        circuitBreaker.reset();
        DegradedTripRecomputeJob job = new DegradedTripRecomputeJob(
                persistenceService,
                calculationService,
                circuitBreaker,
                recomputeStats,
                10
        );

        job.runOnce(); // 1st run recovers trip and creates adjustment
        job.runOnce(); // 2nd run: trip is already CALCULATED, so no-op

        List<BillingAdjustment> adjustments = adjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, TENANT);
        assertThat(adjustments).hasSize(1); // Exactly 1 adjustment created

        // Verify JSON response formatting for GET /trips/{id}/billing-adjustments
        MvcResult mvcResult = mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deltaMeters").isNumber())
                .andExpect(jsonPath("$[0].deltaKm").isNumber())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(jsonNode.isArray()).isTrue();
        assertThat(jsonNode.get(0).hasNonNull("deltaKm")).isTrue();
        assertThat(jsonNode.get(0).hasNonNull("deltaMeters")).isTrue();
    }
}
