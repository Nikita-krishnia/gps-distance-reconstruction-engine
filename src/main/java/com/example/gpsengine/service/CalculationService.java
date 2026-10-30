package com.example.gpsengine.service;

import com.example.gpsengine.api.dto.BillingAdjustmentDto;
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
import com.example.gpsengine.engine.TimelineFingerprint;
import com.example.gpsengine.engine.TripPoint;
import com.example.gpsengine.engine.TripTimelineMerger;
import com.example.gpsengine.error.ConflictException;
import com.example.gpsengine.error.ResourceNotFoundException;
import com.example.gpsengine.error.UnprocessableException;
import com.example.gpsengine.routing.RoutingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service orchestrating idempotent trip distance calculation, invoice closing,
 * and ledgered billing adjustments.
 */
@Service
public class CalculationService {

    private static final Logger log = LoggerFactory.getLogger(CalculationService.class);

    private final TripDataPersistenceService persistenceService;
    private final RoutingProvider routingProvider;
    private final ConfigFingerprintService configFingerprintService;
    private final GpsCleaningConfig gpsCleaningConfig;
    private final AnchorConfig anchorConfig;
    private final SegmentConfig segmentConfig;
    private final com.example.gpsengine.monitoring.GpsMetrics metrics;

    private final GpsCleaner cleaner = new GpsCleaner();
    private final AnchorBuilder anchorBuilder = new AnchorBuilder();
    private final TripTimelineMerger timelineMerger = new TripTimelineMerger();
    private final DistanceEngine distanceEngine = new DistanceEngine();

    public CalculationService(
            TripDataPersistenceService persistenceService,
            RoutingProvider routingProvider,
            ConfigFingerprintService configFingerprintService,
            Optional<GpsCleaningConfig> cleaningConfig,
            Optional<AnchorConfig> anchorConfig,
            Optional<SegmentConfig> segmentConfig) {
        this(persistenceService, routingProvider, configFingerprintService, cleaningConfig, anchorConfig, segmentConfig, Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CalculationService(
            TripDataPersistenceService persistenceService,
            RoutingProvider routingProvider,
            ConfigFingerprintService configFingerprintService,
            Optional<GpsCleaningConfig> cleaningConfig,
            Optional<AnchorConfig> anchorConfig,
            Optional<SegmentConfig> segmentConfig,
            Optional<com.example.gpsengine.monitoring.GpsMetrics> metrics) {
        this.persistenceService = persistenceService;
        this.routingProvider = routingProvider;
        this.configFingerprintService = configFingerprintService;
        this.gpsCleaningConfig = cleaningConfig.orElseGet(GpsCleaningConfig::defaults);
        this.anchorConfig = anchorConfig.orElseGet(AnchorConfig::defaults);
        this.segmentConfig = segmentConfig.orElseGet(SegmentConfig::defaults);
        this.metrics = metrics.orElse(null);
    }

    public record PipelinePrep(
            Trip trip,
            List<TripPoint> timeline,
            String inputHash,
            String ruleVersion
    ) {}

    public PipelinePrep prepareTimeline(Long tripId, String tenantId) {
        TripDataPersistenceService.LoadedTripData data = persistenceService.loadTripData(tripId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        Trip trip = data.trip();
        List<TripMilestone> milestones = data.milestones();
        List<GpsPing> pings = data.pings();

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

        List<GpsPoint> rawGpsPoints = pings.stream()
                .map(p -> new GpsPoint(p.getLat(), p.getLon(), p.getRecordedAt(), p.getAccuracyMeters()))
                .toList();
        CleaningResult cleaningResult = cleaner.clean(rawGpsPoints, gpsCleaningConfig);
        if (metrics != null) {
            metrics.recordPingsRemoved(cleaningResult);
        }

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

        List<TripPoint> timeline = timelineMerger.merge(cleaningResult.cleanedPoints(), anchorResult.anchors());
        String inputHash = TimelineFingerprint.compute(
                timeline,
                configFingerprintService.getFingerprint(),
                DistanceEngine.RULE_VERSION
        );

        return new PipelinePrep(trip, timeline, inputHash, DistanceEngine.RULE_VERSION);
    }

    public CalculationDto.CalculationResponse calculateTripDistance(Long tripId, String tenantId) {
        Instant startTime = Instant.now();

        // Phase 1: Short Read Transaction & Early Reuse Check
        PipelinePrep prep = prepareTimeline(tripId, tenantId);

        // Check if an identical CALCULATED calculation already exists
        Optional<DistanceCalculation> existingCalcOpt = persistenceService.findExistingCalculation(
                tripId, prep.inputHash(), prep.ruleVersion(), TripStatus.CALCULATED, tenantId
        );
        if (existingCalcOpt.isPresent()) {
            DistanceCalculation existing = existingCalcOpt.get();
            Duration duration = Duration.between(startTime, Instant.now());
            if (metrics != null) {
                metrics.recordCalculation("reused", duration);
            }
            log.info("Calculation finished [tripId={}] [status={}] [segmentCount={}] [totalMeters={}] [providerCalls={}] [cacheHits={}] [durationMs={}] [reused={}]",
                    tripId, existing.getStatus(), 0, existing.getTotalMeters(),
                    existing.getRoutingProviderCalls(), existing.getCacheHits(), duration.toMillis(), true);
            return CalculationDto.CalculationResponse.from(
                    existing.getId(),
                    existing.getStatus(),
                    existing.getRuleVersion(),
                    existing.getTotalMeters(),
                    existing.getRoutingProviderCalls(),
                    existing.getCacheHits(),
                    existing.getCacheMisses(),
                    existing.getCreatedAt(),
                    true,
                    null
            );
        }

        // Phase 2: CPU & Routing Pipeline (Outside Database Transaction)
        DistanceResult distanceResult;
        try {
            distanceResult = distanceEngine.calculate(prep.timeline(), routingProvider, segmentConfig);
            if (metrics != null) {
                metrics.recordSegments(distanceResult.ruleCounts());
            }
        } catch (Exception ex) {
            if (metrics != null) {
                metrics.recordCalculationFailed();
            }
            throw ex;
        }

        // Phase 3: Short Write Transaction with Pessimistic Lock
        TripDataPersistenceService.PersistCalculationOutcome outcome;
        try {
            outcome = persistenceService.persistCalculationUnderLock(tripId, tenantId, prep.inputHash(), distanceResult);
        } catch (DataIntegrityViolationException ex) {
            // Safety net: another thread inserted between check and insert
            Optional<DistanceCalculation> conflictWinner = persistenceService.findExistingCalculation(
                    tripId, prep.inputHash(), prep.ruleVersion(), distanceResult.status(), tenantId
            );
            if (conflictWinner.isPresent()) {
                DistanceCalculation winner = conflictWinner.get();
                Duration duration = Duration.between(startTime, Instant.now());
                if (metrics != null) {
                    metrics.recordCalculation("reused", duration);
                }
                log.info("Calculation finished [tripId={}] [status={}] [segmentCount={}] [totalMeters={}] [providerCalls={}] [cacheHits={}] [durationMs={}] [reused={}]",
                        tripId, winner.getStatus(), distanceResult.segments().size(), winner.getTotalMeters(),
                        winner.getRoutingProviderCalls(), winner.getCacheHits(), duration.toMillis(), true);
                return CalculationDto.CalculationResponse.from(
                        winner.getId(),
                        winner.getStatus(),
                        winner.getRuleVersion(),
                        winner.getTotalMeters(),
                        winner.getRoutingProviderCalls(),
                        winner.getCacheHits(),
                        winner.getCacheMisses(),
                        winner.getCreatedAt(),
                        true,
                        null
                );
            }
            if (metrics != null) {
                metrics.recordCalculationFailed();
            }
            throw ex;
        }

        BillingAdjustmentDto.AdjustmentResponse adjustmentDto = null;
        if (outcome.adjustment() != null) {
            BillingAdjustment a = outcome.adjustment();
            if (metrics != null) {
                metrics.recordBillingAdjustment(Math.abs(a.getDeltaMeters()));
            }
            adjustmentDto = BillingAdjustmentDto.AdjustmentResponse.from(
                    a.getId(),
                    a.getOldCalculation().getId(),
                    a.getNewCalculation().getId(),
                    a.getDeltaMeters(),
                    a.getCreatedAt()
            );
        }

        Duration duration = Duration.between(startTime, Instant.now());
        if (metrics != null) {
            String statusTag = outcome.calculation().getStatus() == TripStatus.CALCULATED ? "calculated" : "degraded";
            metrics.recordCalculation(statusTag, duration);
        }

        if (outcome.calculation().getStatus() == TripStatus.DEGRADED) {
            log.warn("Calculation resulted in DEGRADED status [tripId={}] [providerCalls={}]",
                    tripId, outcome.calculation().getRoutingProviderCalls());
        }

        log.info("Calculation finished [tripId={}] [status={}] [segmentCount={}] [totalMeters={}] [providerCalls={}] [cacheHits={}] [durationMs={}] [reused={}]",
                tripId, outcome.calculation().getStatus(), distanceResult.segments().size(),
                outcome.calculation().getTotalMeters(), outcome.calculation().getRoutingProviderCalls(),
                outcome.calculation().getCacheHits(), duration.toMillis(), outcome.reused());

        return CalculationDto.CalculationResponse.from(
                outcome.calculation().getId(),
                outcome.calculation().getStatus(),
                outcome.calculation().getRuleVersion(),
                outcome.calculation().getTotalMeters(),
                outcome.calculation().getRoutingProviderCalls(),
                outcome.calculation().getCacheHits(),
                outcome.calculation().getCacheMisses(),
                outcome.calculation().getCreatedAt(),
                outcome.reused(),
                adjustmentDto
        );
    }

    public TripDto.CloseInvoiceResponse closeInvoice(Long tripId, String tenantId) {
        // Phase 1: Clean and fingerprint current state without routing
        PipelinePrep prep = prepareTimeline(tripId, tenantId);

        // Phase 2: Lock trip and close atomically in short write transaction
        TripDataPersistenceService.CloseInvoiceOutcome outcome =
                persistenceService.closeInvoiceUnderLock(tripId, tenantId, prep.inputHash());

        if (metrics != null) {
            metrics.recordInvoiceClosed();
        }

        log.info("Invoice closed [tripId={}] [billedCalcId={}] [billedMeters={}]",
                tripId, outcome.billedCalculationId(), outcome.billedMeters());

        return TripDto.CloseInvoiceResponse.from(
                outcome.billedCalculationId(),
                outcome.billedMeters(),
                outcome.closedAt()
        );
    }
}
