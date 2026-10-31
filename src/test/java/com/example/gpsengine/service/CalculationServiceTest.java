package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.config.ConfigFingerprintService;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.SegmentResult;
import com.example.gpsengine.error.ResourceNotFoundException;
import com.example.gpsengine.error.UnprocessableException;
import com.example.gpsengine.routing.FakeRoutingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CalculationServiceTest {

    @Mock
    private TripDataPersistenceService persistenceService;

    private CalculationService calculationService;
    private final FakeRoutingProvider fakeRoutingProvider = new FakeRoutingProvider(1.3);

    private static final String TENANT = "tenant-test";

    @BeforeEach
    void setUp() {
        ConfigFingerprintService configFingerprintService = new ConfigFingerprintService(
                GpsCleaningConfig.defaults(),
                AnchorConfig.defaults(),
                SegmentConfig.defaults()
        );
        calculationService = new CalculationService(
                persistenceService,
                fakeRoutingProvider,
                configFingerprintService,
                Optional.of(GpsCleaningConfig.defaults()),
                Optional.of(AnchorConfig.defaults()),
                Optional.of(SegmentConfig.defaults())
        );
    }

    @Test
    @DisplayName("Trip not found throws ResourceNotFoundException")
    void tripNotFoundThrowsException() {
        when(persistenceService.loadTripData(999L, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calculationService.calculateTripDistance(999L, TENANT))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Trip not found");
    }

    @Test
    @DisplayName("Missing TRIP_START or TRIP_END with timestamps throws UnprocessableException")
    void missingTripBoundaryThrowsUnprocessable() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        TripMilestone startOnly = DomainFactory.createMilestone(
                TENANT, trip, MilestoneType.TRIP_START, null, null, null,
                Instant.parse("2026-10-30T07:00:00Z"), null, null, null, null, null, null, null
        );

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(trip, List.of(), List.of(startOnly))));

        assertThatThrownBy(() -> calculationService.calculateTripDistance(1L, TENANT))
                .isInstanceOf(UnprocessableException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MISSING_TRIP_BOUNDARY");
    }

    @Test
    @DisplayName("Successful calculation maps pipeline to JPA entities and saves with sequence numbers")
    void successfulPipelineExecutionAndPersistence() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);
        Instant t2 = t0.plusSeconds(60);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        GpsPing p2 = DomainFactory.createPing(TENANT, trip, 12.9730, 77.5960, t1, 10.0);
        GpsPing p3 = DomainFactory.createPing(TENANT, trip, 12.9279, 77.6271, t2, 10.0);

        TripMilestone start = DomainFactory.createMilestone(
                TENANT, trip, MilestoneType.TRIP_START, null, null, null,
                t0, null, null, null, null, null, null, null
        );
        TripMilestone end = DomainFactory.createMilestone(
                TENANT, trip, MilestoneType.TRIP_END, null, 12.9279, 77.6271,
                t2, null, null, null, null, null, null, null
        );

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(trip, List.of(p1, p2, p3), List.of(start, end))));

        DistanceCalculation mockSavedCalc = DomainFactory.createCalculation(
                TENANT, trip, "hash123", "2026.1", 1500L, TripStatus.CALCULATED, 0, 0, 0
        );
        ArgumentCaptor<DistanceResult> resultCaptor = ArgumentCaptor.forClass(DistanceResult.class);
        when(persistenceService.persistCalculationUnderLock(eq(1L), eq(TENANT), any(), resultCaptor.capture()))
                .thenReturn(new TripDataPersistenceService.PersistCalculationOutcome(mockSavedCalc, false, null));

        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(1L, TENANT);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TripStatus.CALCULATED);

        DistanceResult capturedResult = resultCaptor.getValue();
        List<SegmentResult> savedSegments = capturedResult.segments();
        assertThat(savedSegments).isNotEmpty();
        for (int i = 0; i < savedSegments.size(); i++) {
            assertThat(savedSegments.get(i).sequenceNumber()).isEqualTo(i + 1);
            assertThat(savedSegments.get(i).rule()).isNotNull();
            assertThat(savedSegments.get(i).reason()).isNotBlank();
        }
    }
}
