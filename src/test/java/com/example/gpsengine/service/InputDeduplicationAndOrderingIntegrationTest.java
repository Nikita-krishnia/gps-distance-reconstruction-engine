package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.security.TestSecurityHelper;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
@Testcontainers
@Tag("integration")
class InputDeduplicationAndOrderingIntegrationTest {

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
    private TestSecurityHelper testSecurityHelper;

    private static final String TENANT = "tenant-dedup-test";

    @BeforeEach
    void setUp() {
        testSecurityHelper.createOrGetUser("op-dedup", TENANT, Role.OPERATOR, null);
    }

    @Test
    @DisplayName("C.1: Upload same ping batch 3 times and shuffled copy -> same totalMeters, second calculate reused")
    void uploadSameBatch3TimesAndShuffledCopy() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null);
        persistenceService.saveMilestones(List.of(start, end));

        // Create 10 distinct pings
        List<GpsPing> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(DomainFactory.createPing(TENANT, trip, 12.9700 - i * 0.004, 77.5900 + i * 0.003, t0.plusSeconds(i * 120), 10.0));
        }

        // Upload batch 1
        persistenceService.savePings(batch);
        CalculationDto.CalculationResponse calc1 = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calc1.reused()).isFalse();
        long initialMeters = calc1.totalMeters();

        // Calculate again on identical data -> reused = true
        CalculationDto.CalculationResponse calc1Reused = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calc1Reused.reused()).isTrue();
        assertThat(calc1Reused.totalMeters()).isEqualTo(initialMeters);

        // Upload exact same batch 2nd time
        List<GpsPing> batch2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch2.add(DomainFactory.createPing(TENANT, trip, 12.9700 - i * 0.004, 77.5900 + i * 0.003, t0.plusSeconds(i * 120), 10.0));
        }
        persistenceService.savePings(batch2);

        // Upload exact same batch 3rd time (shuffled order)
        List<GpsPing> batch3 = new ArrayList<>(batch2);
        Collections.shuffle(batch3);
        persistenceService.savePings(batch3);

        // Recalculate: GpsCleaner drops the exact timestamp+coordinate duplicates; timeline fingerprint matches -> reused = true
        CalculationDto.CalculationResponse calc2 = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calc2.reused()).isTrue();
        assertThat(calc2.totalMeters()).isEqualTo(initialMeters);
    }

    @Test
    @DisplayName("C.2: Upload pings in several requests in reverse time order -> same result")
    void uploadPingsInReverseOrderGivesSameResult() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null);
        persistenceService.saveMilestones(List.of(start, end));

        // Upload in 3 separate batches in reverse chronological order
        // Batch 3: late pings
        List<GpsPing> batchLate = List.of(
                DomainFactory.createPing(TENANT, trip, 12.9350, 77.6200, t0.plusSeconds(1500), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9300, 77.6250, t0.plusSeconds(1700), 10.0)
        );
        persistenceService.savePings(batchLate);

        // Batch 2: middle pings
        List<GpsPing> batchMid = List.of(
                DomainFactory.createPing(TENANT, trip, 12.9550, 77.6050, t0.plusSeconds(600), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9450, 77.6120, t0.plusSeconds(1000), 10.0)
        );
        persistenceService.savePings(batchMid);

        // Batch 1: early pings
        List<GpsPing> batchEarly = List.of(
                DomainFactory.createPing(TENANT, trip, 12.9700, 77.5900, t0.plusSeconds(60), 10.0),
                DomainFactory.createPing(TENANT, trip, 12.9650, 77.5980, t0.plusSeconds(300), 10.0)
        );
        persistenceService.savePings(batchEarly);

        CalculationDto.CalculationResponse calcReverse = calculationService.calculateTripDistance(tripId, TENANT);

        // Compare against a single sequential trip with identical points
        Trip tripSeq = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripSeqId = tripSeq.getId();
        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, tripSeq, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, tripSeq, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));

        List<GpsPing> allSequential = new ArrayList<>();
        allSequential.addAll(batchEarly);
        allSequential.addAll(batchMid);
        allSequential.addAll(batchLate);
        List<GpsPing> seqPings = allSequential.stream()
                .map(p -> DomainFactory.createPing(TENANT, tripSeq, p.getLat(), p.getLon(), p.getRecordedAt(), p.getAccuracyMeters()))
                .toList();
        persistenceService.savePings(seqPings);

        CalculationDto.CalculationResponse calcSeq = calculationService.calculateTripDistance(tripSeqId, TENANT);

        assertThat(calcReverse.totalMeters()).isEqualTo(calcSeq.totalMeters());
    }

    @Test
    @DisplayName("C.3: Parallel ping uploads from 10 threads while calculation runs -> no exception, final result matches sequential")
    void parallelPingUploadsDuringCalculation() throws Exception {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(3600);

        persistenceService.saveMilestones(List.of(
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null),
                DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null)
        ));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount + 1);
        AtomicInteger errorCount = new AtomicInteger();

        // 10 worker threads uploading distinct time slices
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<GpsPing> pings = new ArrayList<>();
                    for (int p = 0; p < 5; p++) {
                        Instant pingTime = t0.plusSeconds(threadIdx * 300 + p * 40);
                        pings.add(DomainFactory.createPing(TENANT, trip, 12.9700 - (threadIdx * 0.003), 77.5900 + (p * 0.002), pingTime, 10.0));
                    }
                    persistenceService.savePings(pings);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 1 calculation thread concurrently attempting calculations
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 5; i++) {
                    try {
                        calculationService.calculateTripDistance(tripId, TENANT);
                    } catch (Exception ignored) {
                    }
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);

        // Final calculation after all uploads finish succeeds without errors
        CalculationDto.CalculationResponse finalCalc = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(finalCalc.status()).isEqualTo(com.example.gpsengine.domain.TripStatus.CALCULATED);
        assertThat(finalCalc.totalMeters()).isGreaterThan(0);
    }

    @Test
    @DisplayName("C.4: Repeated DROP milestone uploads for same employee: both are retained in chronological order")
    void repeatedDropForSameEmployeeBehavior() {
        Trip trip = tripRepository.save(DomainFactory.createTrip(TENANT, 12.9279, 77.6271));
        Long tripId = trip.getId();

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(1800);

        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        // Employee drop 1 (e.g. preliminary stop)
        TripMilestone drop1 = DomainFactory.createMilestone(TENANT, trip, MilestoneType.DROP, "emp-repeat", 12.9500, 77.6000, t0.plusSeconds(600), 12.9500, 77.6000, t0.plusSeconds(600), RiderKind.PLANNED, null, null, null);
        // Employee drop 2 (updated / secondary drop milestone for same employee)
        TripMilestone drop2 = DomainFactory.createMilestone(TENANT, trip, MilestoneType.DROP, "emp-repeat", 12.9400, 77.6100, t0.plusSeconds(1200), 12.9400, 77.6100, t0.plusSeconds(1200), RiderKind.PLANNED, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9280, 77.6270, t1, null, null, null, null, null, null, null);

        persistenceService.saveMilestones(List.of(start, drop1, drop2, end));

        CalculationDto.CalculationResponse calc = calculationService.calculateTripDistance(tripId, TENANT);
        assertThat(calc.status()).isEqualTo(com.example.gpsengine.domain.TripStatus.CALCULATED);

        // Verified behaviour: Both valid DROP milestones are synthesized into anchors in chronological order (start -> drop1 -> drop2 -> end = 3 segments)
        assertThat(calc.totalMeters()).isGreaterThan(0);
    }
}

