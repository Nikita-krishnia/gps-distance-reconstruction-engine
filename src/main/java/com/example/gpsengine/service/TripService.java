package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.BillingAdjustmentDto;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TripService {

    private final TripDataPersistenceService persistenceService;
    private final Clock clock;

    public TripService(TripDataPersistenceService persistenceService, Optional<Clock> clock) {
        this.persistenceService = persistenceService;
        this.clock = clock.orElseGet(Clock::systemUTC);
    }

    public TripDto.TripResponse createTrip(TripDto.CreateTripRequest request, String tenantId) {
        Trip trip = DomainFactory.createTrip(
                tenantId,
                request.officePlannedLatitude(),
                request.officePlannedLongitude()
        );
        Trip saved = persistenceService.saveTrip(trip);
        return new TripDto.TripResponse(
                saved.getId(),
                saved.getStatus(),
                saved.getBillingStatus(),
                saved.getCreatedAt()
        );
    }

    public PingDto.UploadPingsResponse uploadPings(Long tripId, String tenantId, PingDto.UploadPingsRequest request) {
        Trip trip = persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        Instant maxAllowedFutureTime = clock.instant().plusSeconds(86400); // 1 day in the future

        List<GpsPing> entities = new ArrayList<>(request.pings().size());
        for (PingDto.PingItem item : request.pings()) {
            if (item.recordedAt().isAfter(maxAllowedFutureTime)) {
                throw new IllegalArgumentException("Ping timestamp cannot be more than 1 day in the future");
            }
            entities.add(DomainFactory.createPing(
                    tenantId,
                    trip,
                    item.latitude(),
                    item.longitude(),
                    item.recordedAt(),
                    item.accuracyMeters()
            ));
        }

        List<GpsPing> saved = persistenceService.savePings(entities);
        return new PingDto.UploadPingsResponse(saved.size());
    }

    public MilestoneDto.UploadMilestonesResponse uploadMilestones(Long tripId, String tenantId, MilestoneDto.UploadMilestonesRequest request) {
        Trip trip = persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        Instant maxAllowedFutureTime = clock.instant().plusSeconds(86400);

        List<TripMilestone> entities = new ArrayList<>(request.milestones().size());
        for (MilestoneDto.MilestoneItem item : request.milestones()) {
            if (item.actualTime() != null && item.actualTime().isAfter(maxAllowedFutureTime)) {
                throw new IllegalArgumentException("Milestone actualTime cannot be more than 1 day in the future");
            }
            if (item.plannedTime() != null && item.plannedTime().isAfter(maxAllowedFutureTime)) {
                throw new IllegalArgumentException("Milestone plannedTime cannot be more than 1 day in the future");
            }
            if (item.profileSignInTime() != null && item.profileSignInTime().isAfter(maxAllowedFutureTime)) {
                throw new IllegalArgumentException("Milestone profileSignInTime cannot be more than 1 day in the future");
            }

            entities.add(DomainFactory.createMilestone(
                    tenantId,
                    trip,
                    item.type(),
                    item.employeeRef(),
                    item.actualLatitude(),
                    item.actualLongitude(),
                    item.actualTime(),
                    item.plannedLatitude(),
                    item.plannedLongitude(),
                    item.plannedTime(),
                    item.riderKind(),
                    item.profileLatitude(),
                    item.profileLongitude(),
                    item.profileSignInTime()
            ));
        }

        List<TripMilestone> saved = persistenceService.saveMilestones(entities);
        return new MilestoneDto.UploadMilestonesResponse(saved.size());
    }

    @Transactional(readOnly = true)
    public TripDto.TripDetailResponse getTripDetails(Long tripId, String tenantId) {
        Trip trip = persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        long pingsCount = persistenceService.countPings(tripId, tenantId);
        long milestonesCount = persistenceService.countMilestones(tripId, tenantId);
        List<DistanceCalculation> calcs = persistenceService.findCalculations(tripId, tenantId);

        CalculationDto.CalculationSummaryResponse latestSummary = null;
        if (!calcs.isEmpty()) {
            DistanceCalculation latest = calcs.get(0);
            latestSummary = CalculationDto.CalculationSummaryResponse.from(
                    latest.getId(),
                    latest.getStatus(),
                    latest.getRuleVersion(),
                    latest.getTotalMeters(),
                    latest.getCreatedAt()
            );
        }

        Long billedCalculationId = null;
        Long billedMeters = null;
        Long adjustmentsTotalMeters = null;
        Long payableMeters = null;
        Instant closedAt = null;

        if (trip.getBillingStatus() == BillingStatus.CLOSED) {
            DistanceCalculation billed = trip.getBilledCalculation();
            if (billed != null) {
                billedCalculationId = billed.getId();
                billedMeters = billed.getTotalMeters();
            }
            adjustmentsTotalMeters = persistenceService.sumAdjustments(tripId, tenantId);
            if (billedMeters != null) {
                payableMeters = billedMeters + adjustmentsTotalMeters;
            }
            closedAt = trip.getClosedAt();
        }

        return new TripDto.TripDetailResponse(
                trip.getId(),
                trip.getStatus(),
                trip.getBillingStatus(),
                trip.getCreatedAt(),
                pingsCount,
                milestonesCount,
                latestSummary,
                billedCalculationId,
                billedMeters,
                adjustmentsTotalMeters,
                payableMeters,
                closedAt
        );
    }

    public List<CalculationDto.CalculationResponse> getCalculationHistory(Long tripId, String tenantId) {
        persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        List<DistanceCalculation> calcs = persistenceService.findCalculations(tripId, tenantId);
        return calcs.stream()
                .map(c -> CalculationDto.CalculationResponse.from(
                        c.getId(),
                        c.getStatus(),
                        c.getRuleVersion(),
                        c.getTotalMeters(),
                        c.getRoutingProviderCalls(),
                        c.getCacheHits(),
                        c.getCacheMisses(),
                        c.getCreatedAt()
                )).toList();
    }

    public List<CalculationDto.SegmentResponse> getCalculationSegments(Long tripId, Long calculationId, String tenantId) {
        persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        DistanceCalculation calculation = persistenceService.findCalculation(calculationId, tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Calculation not found"));

        List<TripSegment> segments = persistenceService.findSegments(calculation.getId());
        return segments.stream()
                .map(s -> new CalculationDto.SegmentResponse(
                        s.getSequenceNumber(),
                        s.getFromLat(),
                        s.getFromLon(),
                        s.getFromTime(),
                        s.getToLat(),
                        s.getToLon(),
                        s.getToTime(),
                        s.getMeters(),
                        s.getRule(),
                        s.getReason()
                )).toList();
    }

    public List<BillingAdjustmentDto.AdjustmentResponse> getBillingAdjustments(Long tripId, String tenantId) {
        persistenceService.findTrip(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        List<BillingAdjustment> adjustments = persistenceService.findAdjustments(tripId, tenantId);
        return adjustments.stream()
                .map(a -> BillingAdjustmentDto.AdjustmentResponse.from(
                        a.getId(),
                        a.getOldCalculation() != null ? a.getOldCalculation().getId() : null,
                        a.getNewCalculation() != null ? a.getNewCalculation().getId() : null,
                        a.getDeltaMeters(),
                        a.getCreatedAt()
                )).toList();
    }
}
