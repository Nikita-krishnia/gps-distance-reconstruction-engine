package com.example.gpsengine.service;

import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.SegmentResult;
import com.example.gpsengine.error.ConflictException;
import com.example.gpsengine.error.ResourceNotFoundException;
import com.example.gpsengine.error.UnprocessableException;
import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.GpsPingRepository;
import com.example.gpsengine.repository.TripMilestoneRepository;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dedicated persistence worker executing database operations in isolated, fine-grained transactions.
 */
@Service
public class TripDataPersistenceService {

    private final TripRepository tripRepository;
    private final GpsPingRepository gpsPingRepository;
    private final TripMilestoneRepository tripMilestoneRepository;
    private final DistanceCalculationRepository distanceCalculationRepository;
    private final TripSegmentRepository tripSegmentRepository;
    private final BillingAdjustmentRepository billingAdjustmentRepository;

    public TripDataPersistenceService(
            TripRepository tripRepository,
            GpsPingRepository gpsPingRepository,
            TripMilestoneRepository tripMilestoneRepository,
            DistanceCalculationRepository distanceCalculationRepository,
            TripSegmentRepository tripSegmentRepository,
            BillingAdjustmentRepository billingAdjustmentRepository) {
        this.tripRepository = tripRepository;
        this.gpsPingRepository = gpsPingRepository;
        this.tripMilestoneRepository = tripMilestoneRepository;
        this.distanceCalculationRepository = distanceCalculationRepository;
        this.tripSegmentRepository = tripSegmentRepository;
        this.billingAdjustmentRepository = billingAdjustmentRepository;
    }

    public record LoadedTripData(
            Trip trip,
            List<GpsPing> pings,
            List<TripMilestone> milestones
    ) {}

    @Transactional(readOnly = true)
    public Optional<LoadedTripData> loadTripData(Long tripId, String tenantId) {
        Optional<Trip> tripOpt = tripRepository.findByIdAndTenantId(tripId, tenantId);
        if (tripOpt.isEmpty()) {
            return Optional.empty();
        }
        Trip trip = tripOpt.get();
        List<GpsPing> pings = gpsPingRepository.findByTripIdAndTenantIdOrderByRecordedAtAsc(tripId, tenantId);
        List<TripMilestone> milestones = tripMilestoneRepository.findByTripIdAndTenantId(tripId, tenantId);
        return Optional.of(new LoadedTripData(trip, pings, milestones));
    }

    @Transactional(readOnly = true)
    public Optional<DistanceCalculation> findExistingCalculation(
            Long tripId, String inputHash, String ruleVersion, TripStatus status, String tenantId) {
        return distanceCalculationRepository.findByTripIdAndInputHashAndRuleVersionAndStatusAndTenantId(
                tripId, inputHash, ruleVersion, status, tenantId
        );
    }

    @Transactional
    public Optional<Trip> lockTripForUpdate(Long tripId, String tenantId) {
        return tripRepository.findByIdAndTenantIdForUpdate(tripId, tenantId);
    }

    @Transactional
    public DistanceCalculation saveCalculation(
            Trip trip,
            DistanceCalculation calculation,
            List<SegmentResult> engineSegments,
            String tenantId) {
        tripRepository.save(trip);
        DistanceCalculation savedCalc = distanceCalculationRepository.save(calculation);

        List<TripSegment> entitySegments = new ArrayList<>(engineSegments.size());
        for (SegmentResult s : engineSegments) {
            TripSegment seg = DomainFactory.createSegment(
                    tenantId,
                    savedCalc,
                    s.sequenceNumber(),
                    s.from().latitude(),
                    s.from().longitude(),
                    s.from().timestamp(),
                    s.to().latitude(),
                    s.to().longitude(),
                    s.to().timestamp(),
                    s.meters(),
                    s.rule(),
                    s.reason()
            );
            entitySegments.add(seg);
        }

        tripSegmentRepository.saveAll(entitySegments);
        return savedCalc;
    }

    @Transactional
    public BillingAdjustment saveAdjustment(BillingAdjustment adjustment) {
        return billingAdjustmentRepository.save(adjustment);
    }

    @Transactional(readOnly = true)
    public Optional<BillingAdjustment> findLatestAdjustment(Long tripId, String tenantId) {
        return billingAdjustmentRepository.findLatestByTripIdAndTenantId(tripId, tenantId);
    }

    @Transactional(readOnly = true)
    public long sumAdjustments(Long tripId, String tenantId) {
        return billingAdjustmentRepository.sumDeltaMetersByTripIdAndTenantId(tripId, tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<Trip> findTrip(Long tripId, String tenantId) {
        return tripRepository.findByIdAndTenantId(tripId, tenantId);
    }

    @Transactional
    public Trip saveTrip(Trip trip) {
        return tripRepository.save(trip);
    }

    @Transactional
    public List<GpsPing> savePings(List<GpsPing> pings) {
        return gpsPingRepository.saveAll(pings);
    }

    @Transactional
    public List<TripMilestone> saveMilestones(List<TripMilestone> milestones) {
        return tripMilestoneRepository.saveAll(milestones);
    }

    @Transactional(readOnly = true)
    public List<DistanceCalculation> findCalculations(Long tripId, String tenantId) {
        return distanceCalculationRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<DistanceCalculation> findCalculation(Long calculationId, Long tripId, String tenantId) {
        return distanceCalculationRepository.findById(calculationId)
                .filter(c -> c.getTrip().getId().equals(tripId) && c.getTenantId().equals(tenantId));
    }

    @Transactional(readOnly = true)
    public List<TripSegment> findSegments(Long calculationId) {
        return tripSegmentRepository.findByCalculationIdOrderBySequenceNumberAsc(calculationId);
    }

    @Transactional(readOnly = true)
    public List<BillingAdjustment> findAdjustments(Long tripId, String tenantId) {
        return billingAdjustmentRepository.findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, tenantId);
    }

    @Transactional(readOnly = true)
    public long countPings(Long tripId, String tenantId) {
        return gpsPingRepository.findByTripIdAndTenantIdOrderByRecordedAtAsc(tripId, tenantId).size();
    }

    @Transactional(readOnly = true)
    public long countMilestones(Long tripId, String tenantId) {
        return tripMilestoneRepository.findByTripIdAndTenantId(tripId, tenantId).size();
    }

    @Transactional(readOnly = true)
    public List<Trip> findDegradedTrips(int batchSize) {
        return tripRepository.findByStatusOrderByCreatedAtAscIdAsc(TripStatus.DEGRADED, PageRequest.of(0, batchSize))
                .getContent();
    }

    public record PersistCalculationOutcome(
            DistanceCalculation calculation,
            boolean reused,
            BillingAdjustment adjustment
    ) {}

    @Transactional
    public PersistCalculationOutcome persistCalculationUnderLock(
            Long tripId,
            String tenantId,
            String inputHash,
            DistanceResult distanceResult) {

        Trip lockedTrip = tripRepository.findByIdAndTenantIdForUpdate(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        // Re-read under lock for CALCULATED match
        Optional<DistanceCalculation> existingCalculated = distanceCalculationRepository
                .findByTripIdAndInputHashAndRuleVersionAndStatusAndTenantId(
                        tripId, inputHash, distanceResult.ruleVersion(), TripStatus.CALCULATED, tenantId
                );
        if (existingCalculated.isPresent()) {
            return new PersistCalculationOutcome(existingCalculated.get(), true, null);
        }

        // If result is DEGRADED and an identical DEGRADED row exists -> reuse it
        if (distanceResult.status() == TripStatus.DEGRADED) {
            Optional<DistanceCalculation> existingDegraded = distanceCalculationRepository
                    .findByTripIdAndInputHashAndRuleVersionAndStatusAndTenantId(
                            tripId, inputHash, distanceResult.ruleVersion(), TripStatus.DEGRADED, tenantId
                    );
            if (existingDegraded.isPresent()) {
                return new PersistCalculationOutcome(existingDegraded.get(), true, null);
            }
        }

        // Insert new calculation
        DistanceCalculation newCalculation = DomainFactory.createCalculation(
                tenantId,
                lockedTrip,
                inputHash,
                distanceResult.ruleVersion(),
                distanceResult.totalMeters(),
                distanceResult.status(),
                distanceResult.providerCalls(),
                distanceResult.cacheHits(),
                distanceResult.cacheMisses()
        );

        lockedTrip.setStatus(distanceResult.status());
        tripRepository.save(lockedTrip);
        DistanceCalculation savedCalc = distanceCalculationRepository.save(newCalculation);

        List<TripSegment> entitySegments = new ArrayList<>(distanceResult.segments().size());
        for (SegmentResult s : distanceResult.segments()) {
            TripSegment seg = DomainFactory.createSegment(
                    tenantId,
                    savedCalc,
                    s.sequenceNumber(),
                    s.from().latitude(),
                    s.from().longitude(),
                    s.from().timestamp(),
                    s.to().latitude(),
                    s.to().longitude(),
                    s.to().timestamp(),
                    s.meters(),
                    s.rule(),
                    s.reason()
            );
            entitySegments.add(seg);
        }
        tripSegmentRepository.saveAll(entitySegments);

        BillingAdjustment savedAdj = null;

        // If trip is CLOSED and result is CALCULATED -> handle ledger adjustment
        if (lockedTrip.getBillingStatus() == BillingStatus.CLOSED && distanceResult.status() == TripStatus.CALCULATED) {
            DistanceCalculation referenceCalc = null;
            Optional<BillingAdjustment> latestAdj = billingAdjustmentRepository.findLatestByTripIdAndTenantId(tripId, tenantId);
            if (latestAdj.isPresent()) {
                referenceCalc = latestAdj.get().getNewCalculation();
            } else {
                referenceCalc = lockedTrip.getBilledCalculation();
            }

            if (referenceCalc != null) {
                long referenceMeters = referenceCalc.getTotalMeters();
                long deltaMeters = distanceResult.totalMeters() - referenceMeters;

                if (deltaMeters != 0L) {
                    BillingAdjustment adjustment = DomainFactory.createAdjustment(
                            tenantId,
                            lockedTrip,
                            referenceCalc,
                            savedCalc,
                            deltaMeters
                    );
                    savedAdj = billingAdjustmentRepository.save(adjustment);
                }
            }
        }

        return new PersistCalculationOutcome(savedCalc, false, savedAdj);
    }

    public record CloseInvoiceOutcome(
            Long billedCalculationId,
            long billedMeters,
            Instant closedAt
    ) {}

    @Transactional
    public CloseInvoiceOutcome closeInvoiceUnderLock(
            Long tripId,
            String tenantId,
            String expectedInputHash) {

        Trip lockedTrip = tripRepository.findByIdAndTenantIdForUpdate(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        if (lockedTrip.getBillingStatus() == BillingStatus.CLOSED) {
            DistanceCalculation billed = lockedTrip.getBilledCalculation();
            return new CloseInvoiceOutcome(
                    billed != null ? billed.getId() : null,
                    billed != null ? billed.getTotalMeters() : 0L,
                    lockedTrip.getClosedAt()
            );
        }

        List<DistanceCalculation> history = distanceCalculationRepository
                .findByTripIdAndTenantIdOrderByCreatedAtDesc(tripId, tenantId);
        if (history.isEmpty()) {
            throw new UnprocessableException("NO_CALCULATION", "Cannot close invoice: no calculation has been performed for this trip");
        }

        DistanceCalculation latestCalc = history.get(0);
        if (latestCalc.getStatus() == TripStatus.DEGRADED) {
            throw new ConflictException("Cannot close invoice: latest calculation is DEGRADED; estimates cannot be billed");
        }

        if (!latestCalc.getInputHash().equals(expectedInputHash)) {
            throw new ConflictException("Cannot close invoice: data changed since latest calculation; recalculate first");
        }

        Instant closedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        lockedTrip.setBillingStatus(BillingStatus.CLOSED);
        lockedTrip.setBilledCalculation(latestCalc);
        lockedTrip.setClosedAt(closedAt);
        tripRepository.save(lockedTrip);

        return new CloseInvoiceOutcome(latestCalc.getId(), latestCalc.getTotalMeters(), closedAt);
    }
}
