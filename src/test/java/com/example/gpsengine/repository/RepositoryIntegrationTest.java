package com.example.gpsengine.repository;

import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.domain.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private GpsPingRepository gpsPingRepository;

    @Autowired
    private DistanceCalculationRepository distanceCalculationRepository;

    @Autowired
    private TripSegmentRepository tripSegmentRepository;

    @Autowired
    private BillingAdjustmentRepository billingAdjustmentRepository;

    @Test
    @DisplayName("Should save a Trip and read it back by id and matching tenantId")
    void shouldSaveAndReadTripByIdAndTenantId() {
        Trip trip = new Trip(
                "tenant-alpha",
                TripStatus.NEW,
                BillingStatus.OPEN,
                12.9716,
                77.5946,
                Instant.now()
        );
        Trip saved = tripRepository.save(trip);

        Optional<Trip> fetched = tripRepository.findByIdAndTenantId(saved.getId(), "tenant-alpha");

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getTenantId()).isEqualTo("tenant-alpha");
        assertThat(fetched.get().getStatus()).isEqualTo(TripStatus.NEW);
        assertThat(fetched.get().getBillingStatus()).isEqualTo(BillingStatus.OPEN);
        assertThat(fetched.get().getOfficePlannedLat()).isEqualTo(12.9716);
        assertThat(fetched.get().getOfficePlannedLon()).isEqualTo(77.5946);
    }

    @Test
    @DisplayName("Should return empty when querying a Trip with a wrong tenantId")
    void shouldReturnEmptyForWrongTenantId() {
        Trip trip = new Trip(
                "tenant-alpha",
                TripStatus.NEW,
                BillingStatus.OPEN,
                12.9716,
                77.5946,
                Instant.now()
        );
        Trip saved = tripRepository.save(trip);

        Optional<Trip> fetched = tripRepository.findByIdAndTenantId(saved.getId(), "tenant-beta");

        assertThat(fetched).isEmpty();
    }

    @Test
    @DisplayName("Should return GPS pings sorted by recordedAt ascending when saved out of order")
    void shouldReturnPingsSortedByRecordedAtAsc() {
        Trip trip = tripRepository.save(new Trip(
                "tenant-alpha",
                TripStatus.NEW,
                BillingStatus.OPEN,
                12.9716,
                77.5946,
                Instant.now()
        ));

        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant t1 = baseTime.plusSeconds(10);
        Instant t2 = baseTime.plusSeconds(20);
        Instant t3 = baseTime.plusSeconds(30);

        // Save out of chronological order: t3, then t1, then t2
        gpsPingRepository.save(new GpsPing("tenant-alpha", trip, 12.9730, 77.5960, t3, 15.0));
        gpsPingRepository.save(new GpsPing("tenant-alpha", trip, 12.9710, 77.5940, t1, 10.0));
        gpsPingRepository.save(new GpsPing("tenant-alpha", trip, 12.9720, 77.5950, t2, 12.0));

        List<GpsPing> sortedPings = gpsPingRepository.findByTripIdOrderByRecordedAtAsc(trip.getId());

        assertThat(sortedPings).hasSize(3);
        assertThat(sortedPings.get(0).getRecordedAt()).isEqualTo(t1);
        assertThat(sortedPings.get(1).getRecordedAt()).isEqualTo(t2);
        assertThat(sortedPings.get(2).getRecordedAt()).isEqualTo(t3);
    }

    @Test
    @DisplayName("Should save a calculation with 2 segments and read them in sequence order")
    void shouldReadSegmentsInSequenceOrder() {
        Trip trip = tripRepository.save(new Trip(
                "tenant-alpha",
                TripStatus.CALCULATED,
                BillingStatus.OPEN,
                12.9716,
                77.5946,
                Instant.now()
        ));

        DistanceCalculation calc = distanceCalculationRepository.save(new DistanceCalculation(
                "tenant-alpha",
                trip,
                "v1.0.0",
                3500L,
                TripStatus.CALCULATED,
                1,
                1,
                0,
                Instant.now()
        ));

        Instant now = Instant.now();
        // Save segment 2 first, then segment 1
        tripSegmentRepository.save(new TripSegment(
                "tenant-alpha",
                calc,
                2,
                12.9720, 77.5950, now.plusSeconds(60),
                12.9730, 77.5960, now.plusSeconds(120),
                1500L,
                SegmentRule.ROUTED,
                "Valid routing distance"
        ));

        tripSegmentRepository.save(new TripSegment(
                "tenant-alpha",
                calc,
                1,
                12.9710, 77.5940, now,
                12.9720, 77.5950, now.plusSeconds(60),
                2000L,
                SegmentRule.SHORT_GAP_AERIAL,
                "Gap under 70 seconds"
        ));

        List<TripSegment> segments = tripSegmentRepository.findByCalculationIdOrderBySequenceNumberAsc(calc.getId());

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).getSequenceNumber()).isEqualTo(1);
        assertThat(segments.get(0).getMeters()).isEqualTo(2000L);
        assertThat(segments.get(0).getRule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);

        assertThat(segments.get(1).getSequenceNumber()).isEqualTo(2);
        assertThat(segments.get(1).getMeters()).isEqualTo(1500L);
        assertThat(segments.get(1).getRule()).isEqualTo(SegmentRule.ROUTED);
    }

    @Test
    @DisplayName("Should save a BillingAdjustment with a negative delta and read it back")
    void shouldSaveBillingAdjustmentWithNegativeDelta() {
        Trip trip = tripRepository.save(new Trip(
                "tenant-alpha",
                TripStatus.CALCULATED,
                BillingStatus.CLOSED,
                12.9716,
                77.5946,
                Instant.now()
        ));

        DistanceCalculation oldCalc = distanceCalculationRepository.save(new DistanceCalculation(
                "tenant-alpha",
                trip,
                "v1.0.0",
                5000L,
                TripStatus.CALCULATED,
                2,
                0,
                2,
                Instant.now().minusSeconds(3600)
        ));

        DistanceCalculation newCalc = distanceCalculationRepository.save(new DistanceCalculation(
                "tenant-alpha",
                trip,
                "v1.0.0",
                4200L,
                TripStatus.CALCULATED,
                2,
                2,
                0,
                Instant.now()
        ));

        // Negative delta: recalculated distance was shorter by 800 meters
        BillingAdjustment adjustment = new BillingAdjustment(
                "tenant-alpha",
                trip,
                oldCalc,
                newCalc,
                -800L,
                Instant.now()
        );
        BillingAdjustment saved = billingAdjustmentRepository.save(adjustment);

        List<BillingAdjustment> adjustments = billingAdjustmentRepository.findByTripIdOrderByCreatedAtDesc(trip.getId());

        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).getId()).isEqualTo(saved.getId());
        assertThat(adjustments.get(0).getDeltaMeters()).isEqualTo(-800L);
        assertThat(adjustments.get(0).getOldCalculation().getId()).isEqualTo(oldCalc.getId());
        assertThat(adjustments.get(0).getNewCalculation().getId()).isEqualTo(newCalc.getId());
    }
}

