package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.engine.AnchorBuilder;
import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.AnchorResult;
import com.example.gpsengine.engine.CleaningResult;
import com.example.gpsengine.engine.DistanceEngine;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.GpsCleaner;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.GpsPoint;
import com.example.gpsengine.engine.MilestoneInput;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.TripPoint;
import com.example.gpsengine.engine.TripTimelineMerger;
import com.example.gpsengine.error.ConflictException;
import com.example.gpsengine.error.ResourceNotFoundException;
import com.example.gpsengine.error.UnprocessableException;
import com.example.gpsengine.routing.RoutingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service orchestrating the end-to-end trip distance reconstruction pipeline.
 *
 * <p>TRANSACTION BOUNDARY SEPARATION:
 * To avoid holding database locks or open database connection pool connections during potentially
 * slow network calls to the external routing provider, this service splits processing into 3 phases:
 * <ol>
 *   <li><b>Phase 1 (Read Tx):</b> Load entities into memory via {@link TripDataPersistenceService#loadTripData(Long, String)}.</li>
 *   <li><b>Phase 2 (No Tx):</b> Run cleaning, anchor extraction, timeline merging, and segment routing.</li>
 *   <li><b>Phase 3 (Write Tx):</b> Persist {@link DistanceCalculation}, segments, and updated trip status.</li>
 * </ol>
 */
@Service
public class CalculationService {

    private static final Logger log = LoggerFactory.getLogger(CalculationService.class);

    private final TripDataPersistenceService persistenceService;
    private final RoutingProvider routingProvider;
    private final GpsCleaningConfig gpsCleaningConfig;
    private final AnchorConfig anchorConfig;
    private final SegmentConfig segmentConfig;

    private final GpsCleaner cleaner = new GpsCleaner();
    private final AnchorBuilder anchorBuilder = new AnchorBuilder();
    private final TripTimelineMerger timelineMerger = new TripTimelineMerger();
    private final DistanceEngine distanceEngine = new DistanceEngine();

    public CalculationService(
            TripDataPersistenceService persistenceService,
            RoutingProvider routingProvider,
            Optional<GpsCleaningConfig> cleaningConfig,
            Optional<AnchorConfig> anchorConfig,
            Optional<SegmentConfig> segmentConfig) {
        this.persistenceService = persistenceService;
        this.routingProvider = routingProvider;
        this.gpsCleaningConfig = cleaningConfig.orElseGet(GpsCleaningConfig::defaults);
        this.anchorConfig = anchorConfig.orElseGet(AnchorConfig::defaults);
        this.segmentConfig = segmentConfig.orElseGet(SegmentConfig::defaults);
    }

    public CalculationDto.CalculationResponse calculateTripDistance(Long tripId, String tenantId) {
        Instant startTime = Instant.now();

        // Phase 1: Short Read Transaction
        TripDataPersistenceService.LoadedTripData data = persistenceService.loadTripData(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        Trip trip = data.trip();
        if (trip.getBillingStatus() == BillingStatus.CLOSED) {
            throw new ConflictException("Cannot calculate distance: trip billing status is CLOSED");
        }

        List<TripMilestone> milestones = data.milestones();
        List<GpsPing> pings = data.pings();

        // Validate boundary milestones
        Optional<Instant> tripStartOpt = milestones.stream()
                .filter(m -> m.getType() == MilestoneType.TRIP_START && m.getActualTime() != null)
                .map(TripMilestone::getActualTime)
                .min(Comparator.naturalOrder());

        Optional<TripMilestone> tripEndOpt = milestones.stream()
                .filter(m -> m.getType() == MilestoneType.TRIP_END && m.getActualTime() != null)
                .max(Comparator.comparing(TripMilestone::getActualTime));

        if (tripStartOpt.isEmpty() || tripEndOpt.isEmpty()) {
            throw new UnprocessableException(
                    "MISSING_TRIP_BOUNDARY",
                    "Trip must have at least one TRIP_START and one TRIP_END milestone with actual timestamps"
            );
        }

        Instant tripStartTime = tripStartOpt.get();
        TripMilestone tripEndMilestone = tripEndOpt.get();
        Instant tripEndTime = tripEndMilestone.getActualTime();
        Double actualEndLat = tripEndMilestone.getActualLat();
        Double actualEndLon = tripEndMilestone.getActualLon();

        // Phase 2: CPU & Routing Pipeline (Outside Database Transaction)

        // 1. Clean GPS pings
        List<GpsPoint> rawGpsPoints = pings.stream()
                .map(p -> new GpsPoint(p.getLat(), p.getLon(), p.getRecordedAt(), p.getAccuracyMeters()))
                .toList();
        CleaningResult cleaningResult = cleaner.clean(rawGpsPoints, gpsCleaningConfig);

        // 2. Synthesize anchors
        List<MilestoneInput> milestoneInputs = milestones.stream()
                .map(m -> new MilestoneInput(
                        m.getType(),
                        m.getEmployeeRef(),
                        m.getRiderKind(),
                        m.getActualLat(),
                        m.getActualLon(),
                        m.getActualTime(),
                        m.getPlannedLat(),
                        m.getPlannedLon(),
                        m.getPlannedTime(),
                        m.getProfileFallbackLat(),
                        m.getProfileFallbackLon(),
                        m.getProfileSignInTime()
                )).toList();

        AnchorResult anchorResult = anchorBuilder.buildAnchors(
                milestoneInputs,
                tripStartTime,
                tripEndTime,
                actualEndLat,
                actualEndLon,
                trip.getOfficePlannedLat(),
                trip.getOfficePlannedLon(),
                anchorConfig
        );

        // 3. Merge into unified chronological timeline
        List<TripPoint> timeline = timelineMerger.merge(cleaningResult.cleanedPoints(), anchorResult.anchors());

        // 4. Calculate segment distances
        DistanceResult distanceResult = distanceEngine.calculate(timeline, routingProvider, segmentConfig);

        DistanceCalculation calculation = DomainFactory.createCalculation(
                tenantId,
                trip,
                distanceResult.ruleVersion(),
                distanceResult.totalMeters(),
                distanceResult.status(),
                distanceResult.providerCalls(),
                distanceResult.cacheHits(),
                distanceResult.cacheMisses()
        );

        // Update trip status
        trip.setStatus(distanceResult.status());

        // Phase 3: Short Write Transaction
        DistanceCalculation savedCalc = persistenceService.saveCalculation(
                trip, calculation, distanceResult.segments(), tenantId
        );

        Duration duration = Duration.between(startTime, Instant.now());
        // Structured log: no coordinates or employee identifiers
        log.info("Calculation completed [tripId={}] [tenantId={}] [status={}] [segments={}] [totalMeters={}] [providerCalls={}] [durationMillis={}]",
                tripId, tenantId, distanceResult.status(), distanceResult.segments().size(),
                distanceResult.totalMeters(), distanceResult.providerCalls(), duration.toMillis());

        return CalculationDto.CalculationResponse.from(
                savedCalc.getId(),
                savedCalc.getStatus(),
                savedCalc.getRuleVersion(),
                savedCalc.getTotalMeters(),
                savedCalc.getRoutingProviderCalls(),
                savedCalc.getCacheHits(),
                savedCalc.getCacheMisses(),
                savedCalc.getCreatedAt()
        );
    }
}
