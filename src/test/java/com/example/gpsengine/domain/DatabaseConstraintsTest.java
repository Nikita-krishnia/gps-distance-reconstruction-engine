package com.example.gpsengine.domain;

import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.TripRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DatabaseConstraintsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private DistanceCalculationRepository calculationRepository;

    @Autowired
    private BillingAdjustmentRepository adjustmentRepository;

    private static final String TENANT = "tenant-constraint-test";

    @Test
    @DisplayName("Unique constraint uk_calc_trip_hash_ver_status rejects duplicate (trip, inputHash, ruleVersion, status)")
    void duplicateCalculationRejected() {
        Trip trip = tripRepository.save(new Trip(TENANT, TripStatus.NEW, BillingStatus.OPEN, 12.0, 77.0, Instant.now()));

        DistanceCalculation c1 = new DistanceCalculation(
                TENANT, trip, "hash123", "2026.1", 1000L, TripStatus.CALCULATED, 0, 0, 0, Instant.now()
        );
        calculationRepository.saveAndFlush(c1);

        DistanceCalculation c2 = new DistanceCalculation(
                TENANT, trip, "hash123", "2026.1", 1000L, TripStatus.CALCULATED, 0, 0, 0, Instant.now()
        );

        assertThatThrownBy(() -> calculationRepository.saveAndFlush(c2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Unique constraint uk_adjustment_trip_new_calc rejects duplicate (trip, newCalculation)")
    void duplicateAdjustmentRejected() {
        Trip trip = tripRepository.save(new Trip(TENANT, TripStatus.NEW, BillingStatus.OPEN, 12.0, 77.0, Instant.now()));

        DistanceCalculation c1 = calculationRepository.saveAndFlush(new DistanceCalculation(
                TENANT, trip, "hash1", "2026.1", 1000L, TripStatus.CALCULATED, 0, 0, 0, Instant.now()
        ));
        DistanceCalculation c2 = calculationRepository.saveAndFlush(new DistanceCalculation(
                TENANT, trip, "hash2", "2026.1", 1500L, TripStatus.CALCULATED, 0, 0, 0, Instant.now()
        ));

        BillingAdjustment a1 = new BillingAdjustment(TENANT, trip, c1, c2, 500L, Instant.now());
        adjustmentRepository.saveAndFlush(a1);

        BillingAdjustment a2 = new BillingAdjustment(TENANT, trip, c1, c2, 500L, Instant.now());
        assertThatThrownBy(() -> adjustmentRepository.saveAndFlush(a2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

