package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.config.ConfigFingerprintService;
import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.error.ConflictException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CalculationServiceBatch9Test {

    @Mock
    private TripDataPersistenceService persistenceService;

    @Mock
    private ConfigFingerprintService configFingerprintService;

    private CalculationService calculationService;
    private final FakeRoutingProvider fakeRoutingProvider = new FakeRoutingProvider(1.3);

    private static final String TENANT = "tenant-test";

    @BeforeEach
    void setUp() {
        when(configFingerprintService.getFingerprint()).thenReturn("config-fingerprint-v1");
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
    @DisplayName("Idempotent calculate: existing CALCULATED calculation is reused without calling provider or saving new row")
    void existingCalculationReused() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9716, 77.5946, t1, null, null, null, null, null, null, null);

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(trip, List.of(p1), List.of(start, end))));

        DistanceCalculation existing = DomainFactory.createCalculation(
                TENANT, trip, "some-hash", "2026.1", 1000L, TripStatus.CALCULATED, 0, 0, 0
        );
        when(persistenceService.findExistingCalculation(eq(1L), any(), eq("2026.1"), eq(TripStatus.CALCULATED), eq(TENANT)))
                .thenReturn(Optional.of(existing));

        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(1L, TENANT);

        assertThat(response.reused()).isTrue();
        assertThat(response.totalMeters()).isEqualTo(1000L);
        verify(persistenceService, never()).saveCalculation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Closed trip recalculation produces billing adjustment when delta != 0")
    void closedTripRecalculationProducesAdjustment() {
        Trip closedTrip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        closedTrip.setBillingStatus(BillingStatus.CLOSED);

        DistanceCalculation billedCalc = DomainFactory.createCalculation(
                TENANT, closedTrip, "old-hash", "2026.1", 1000L, TripStatus.CALCULATED, 0, 0, 0
        );
        closedTrip.setBilledCalculation(billedCalc);

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, closedTrip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, closedTrip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, closedTrip, MilestoneType.TRIP_END, null, 12.9716, 77.5946, t1, null, null, null, null, null, null, null);

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(closedTrip, List.of(p1), List.of(start, end))));
        when(persistenceService.findExistingCalculation(eq(1L), any(), any(), any(), eq(TENANT)))
                .thenReturn(Optional.empty());

        DistanceCalculation newSavedCalc = DomainFactory.createCalculation(
                TENANT, closedTrip, "new-hash", "2026.1", 1500L, TripStatus.CALCULATED, 0, 0, 0
        );
        BillingAdjustment mockAdj = DomainFactory.createAdjustment(TENANT, closedTrip, billedCalc, newSavedCalc, -1000L);
        when(persistenceService.persistCalculationUnderLock(eq(1L), eq(TENANT), any(), any()))
                .thenReturn(new TripDataPersistenceService.PersistCalculationOutcome(newSavedCalc, false, mockAdj));

        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(1L, TENANT);

        assertThat(response.adjustment()).isNotNull();
        assertThat(response.adjustment().deltaMeters()).isEqualTo(-1000L);
    }

    @Test
    @DisplayName("Closed trip recalculation produces NO adjustment when delta == 0")
    void closedTripRecalculationNoAdjustmentWhenDeltaZero() {
        Trip closedTrip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        closedTrip.setBillingStatus(BillingStatus.CLOSED);

        DistanceCalculation billedCalc = DomainFactory.createCalculation(
                TENANT, closedTrip, "old-hash", "2026.1", 0L, TripStatus.CALCULATED, 0, 0, 0
        );
        closedTrip.setBilledCalculation(billedCalc);

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, closedTrip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, closedTrip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, closedTrip, MilestoneType.TRIP_END, null, 12.9716, 77.5946, t1, null, null, null, null, null, null, null);

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(closedTrip, List.of(p1), List.of(start, end))));
        when(persistenceService.findExistingCalculation(eq(1L), any(), any(), any(), eq(TENANT)))
                .thenReturn(Optional.empty());

        DistanceCalculation newSavedCalc = DomainFactory.createCalculation(
                TENANT, closedTrip, "new-hash", "2026.1", 0L, TripStatus.CALCULATED, 0, 0, 0
        );
        when(persistenceService.persistCalculationUnderLock(eq(1L), eq(TENANT), any(), any()))
                .thenReturn(new TripDataPersistenceService.PersistCalculationOutcome(newSavedCalc, false, null));

        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(1L, TENANT);

        assertThat(response.adjustment()).isNull();
    }

    @Test
    @DisplayName("Invoice closing: delegating validation errors from persistence")
    void invoiceClosingValidationErrors() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9716, 77.5946, t1, null, null, null, null, null, null, null);

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(trip, List.of(p1), List.of(start, end))));

        // 1. No calculation
        when(persistenceService.closeInvoiceUnderLock(eq(1L), eq(TENANT), any()))
                .thenThrow(new UnprocessableException("NO_CALCULATION", "Cannot close invoice: no calculation has been performed for this trip"));
        assertThatThrownBy(() -> calculationService.closeInvoice(1L, TENANT))
                .isInstanceOf(UnprocessableException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NO_CALCULATION");

        // 2. Degraded calculation
        when(persistenceService.closeInvoiceUnderLock(eq(1L), eq(TENANT), any()))
                .thenThrow(new ConflictException("Cannot close invoice: latest calculation is DEGRADED; estimates cannot be billed"));
        assertThatThrownBy(() -> calculationService.closeInvoice(1L, TENANT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DEGRADED");

        // 3. Stale calculation
        when(persistenceService.closeInvoiceUnderLock(eq(1L), eq(TENANT), any()))
                .thenThrow(new ConflictException("Cannot close invoice: data changed since latest calculation; recalculate first"));
        assertThatThrownBy(() -> calculationService.closeInvoice(1L, TENANT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("data changed");
    }

    @Test
    @DisplayName("Invoice closing: closing returns billed result")
    void invoiceClosingIdempotent() {
        Trip trip = DomainFactory.createTrip(TENANT, 12.9279, 77.6271);
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(30);

        GpsPing p1 = DomainFactory.createPing(TENANT, trip, 12.9716, 77.5946, t0, 10.0);
        TripMilestone start = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_START, null, null, null, t0, null, null, null, null, null, null, null);
        TripMilestone end = DomainFactory.createMilestone(TENANT, trip, MilestoneType.TRIP_END, null, 12.9716, 77.5946, t1, null, null, null, null, null, null, null);

        when(persistenceService.loadTripData(1L, TENANT))
                .thenReturn(Optional.of(new TripDataPersistenceService.LoadedTripData(trip, List.of(p1), List.of(start, end))));

        Instant closedAt = Instant.now();
        when(persistenceService.closeInvoiceUnderLock(eq(1L), eq(TENANT), any()))
                .thenReturn(new TripDataPersistenceService.CloseInvoiceOutcome(10L, 2000L, closedAt));

        TripDto.CloseInvoiceResponse r1 = calculationService.closeInvoice(1L, TENANT);
        assertThat(r1.billedCalculationId()).isEqualTo(10L);
        assertThat(r1.billedMeters()).isEqualTo(2000L);
        assertThat(r1.closedAt()).isEqualTo(closedAt);
    }
}

