package com.example.gpsengine.job;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripDataPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DegradedTripRecomputeJobTest {

    @Mock
    private TripDataPersistenceService persistenceService;

    @Mock
    private CalculationService calculationService;

    @Mock
    private CircuitBreaker circuitBreaker;

    private RecomputeStats stats;
    private DegradedTripRecomputeJob job;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        stats = new RecomputeStats();
        job = new DegradedTripRecomputeJob(persistenceService, calculationService, circuitBreaker, stats, 50);
    }

    @Test
    @DisplayName("When circuit breaker is OPEN, runOnce skips execution early without querying persistence")
    void openBreakerSkipsExecution() {
        when(circuitBreaker.isOpen()).thenReturn(true);

        job.runOnce();

        assertThat(stats.snapshot().skippedRuns()).isEqualTo(1);
        verifyNoInteractions(persistenceService);
        verifyNoInteractions(calculationService);
    }

    @Test
    @DisplayName("Recalculation recovering to CALCULATED updates stats and continues")
    void successfulRecoveryUpdatesStats() {
        when(circuitBreaker.isOpen()).thenReturn(false);

        Trip t1 = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        ReflectionTestUtils.setField(t1, "id", 1L);
        when(persistenceService.findDegradedTrips(anyInt())).thenReturn(List.of(t1));

        CalculationDto.CalculationResponse calcResponse = CalculationDto.CalculationResponse.from(
                10L, TripStatus.CALCULATED, "2026.1", 1500L, 2, 0, 2, Instant.now(), false, null
        );
        when(calculationService.calculateTripDistance(1L, TENANT)).thenReturn(calcResponse);

        job.runOnce();

        RecomputeStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.attempted()).isEqualTo(1);
        assertThat(snapshot.recovered()).isEqualTo(1);
        assertThat(snapshot.stillDegraded()).isEqualTo(0);
        assertThat(snapshot.skippedRuns()).isEqualTo(0);
    }

    @Test
    @DisplayName("Recalculation still DEGRADED stops the batch early to avoid hammering provider")
    void stillDegradedStopsBatch() {
        when(circuitBreaker.isOpen()).thenReturn(false);

        Trip t1 = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        ReflectionTestUtils.setField(t1, "id", 1L);
        Trip t2 = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        ReflectionTestUtils.setField(t2, "id", 2L);
        when(persistenceService.findDegradedTrips(anyInt())).thenReturn(List.of(t1, t2));

        CalculationDto.CalculationResponse degradedResponse = CalculationDto.CalculationResponse.from(
                10L, TripStatus.DEGRADED, "2026.1", 1200L, 0, 0, 0, Instant.now(), false, null
        );
        when(calculationService.calculateTripDistance(1L, TENANT)).thenReturn(degradedResponse);

        job.runOnce();

        RecomputeStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.attempted()).isEqualTo(1);
        assertThat(snapshot.recovered()).isEqualTo(0);
        assertThat(snapshot.stillDegraded()).isEqualTo(1);

        // Trip 2 should not have been attempted
        verify(calculationService, never()).calculateTripDistance(2L, TENANT);
    }

    @Test
    @DisplayName("Unexpected exception on one trip does not prevent processing subsequent trips")
    void exceptionOnOneTripDoesNotHaltJob() {
        when(circuitBreaker.isOpen()).thenReturn(false);

        Trip t1 = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        ReflectionTestUtils.setField(t1, "id", 1L);
        Trip t2 = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        ReflectionTestUtils.setField(t2, "id", 2L);
        when(persistenceService.findDegradedTrips(anyInt())).thenReturn(List.of(t1, t2));

        when(calculationService.calculateTripDistance(1L, TENANT))
                .thenThrow(new RuntimeException("Simulated error"));

        CalculationDto.CalculationResponse recoveredResponse = CalculationDto.CalculationResponse.from(
                20L, TripStatus.CALCULATED, "2026.1", 1800L, 1, 0, 1, Instant.now(), false, null
        );
        when(calculationService.calculateTripDistance(2L, TENANT)).thenReturn(recoveredResponse);

        job.runOnce();

        RecomputeStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.attempted()).isEqualTo(2);
        assertThat(snapshot.recovered()).isEqualTo(1);
    }
}

