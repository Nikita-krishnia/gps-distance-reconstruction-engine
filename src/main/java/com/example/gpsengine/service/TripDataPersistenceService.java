package com.example.gpsengine.service;

import com.example.gpsengine.domain.BillingAdjustment;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripSegment;
import com.example.gpsengine.engine.SegmentResult;
import com.example.gpsengine.repository.BillingAdjustmentRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.GpsPingRepository;
import com.example.gpsengine.repository.TripMilestoneRepository;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.repository.TripSegmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dedicated persistence worker executing database operations in isolated, fine-grained transactions.
 *
 * <p>ARCHITECTURAL RATIONALE:
 * Calling @Transactional methods on 'this' inside the same bean bypasses Spring's dynamic AOP proxy,
 * resulting in NO transaction being started. Moving database read and write phases into this separate
 * Spring @Service bean guarantees that Spring's proxy intercepts calls and manages database transaction
 * lifecycles correctly.
 *
 * <p>THREE-PHASE PATTERN:
 * In {@link CalculationService}, database operations are split around the computation engine:
 * <ol>
 *   <li>Phase 1: Short read-only transaction via {@link #loadTripData(Long, String)}</li>
 *   <li>Phase 2: CPU and network intensive engine execution (completely non-transactional)</li>
 *   <li>Phase 3: Short write transaction via {@link #saveCalculation(Trip, DistanceCalculation, List, String)}</li>
 * </ol>
 * This prevents holding database connections or row locks during external network routing queries.
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
}
