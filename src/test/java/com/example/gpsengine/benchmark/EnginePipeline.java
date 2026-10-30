package com.example.gpsengine.benchmark;

import com.example.gpsengine.config.ConfigFingerprintService;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.AnchorBuilder;
import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.AnchorResult;
import com.example.gpsengine.engine.CleaningResult;
import com.example.gpsengine.engine.DistanceEngine;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.GpsCleaner;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.MilestoneInput;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.TimelineFingerprint;
import com.example.gpsengine.engine.TripPoint;
import com.example.gpsengine.engine.TripTimelineMerger;
import com.example.gpsengine.routing.RoutingProvider;

import java.time.Instant;
import java.util.List;

/**
 * Executes the exact plain-Java calculation pipeline matching {@link com.example.gpsengine.service.CalculationService}
 * in the same order without modifying production classes.
 */
public class EnginePipeline {

    public record PipelineResult(
            int tripIndex,
            TripStatus status,
            long totalMeters,
            int providerCalls,
            int cacheHits,
            int cacheMisses,
            String inputHash,
            long pipelineNanos,
            int fallbackAerialCount,
            int shortGapCount,
            int parkedCount,
            int routedCount,
            int rejectedSpeedCount
    ) {}

    private final GpsCleaner cleaner;
    private final AnchorBuilder anchorBuilder;
    private final TripTimelineMerger timelineMerger;
    private final DistanceEngine distanceEngine;
    private final GpsCleaningConfig gpsCleaningConfig;
    private final AnchorConfig anchorConfig;
    private final SegmentConfig segmentConfig;
    private final String configFingerprint;

    public EnginePipeline() {
        this(GpsCleaningConfig.defaults(), AnchorConfig.defaults(), SegmentConfig.defaults());
    }

    public EnginePipeline(GpsCleaningConfig cleaningConfig, AnchorConfig anchorConfig, SegmentConfig segmentConfig) {
        this.cleaner = new GpsCleaner();
        this.anchorBuilder = new AnchorBuilder();
        this.timelineMerger = new TripTimelineMerger();
        this.distanceEngine = new DistanceEngine();
        this.gpsCleaningConfig = cleaningConfig;
        this.anchorConfig = anchorConfig;
        this.segmentConfig = segmentConfig;

        ConfigFingerprintService fingerprintService = new ConfigFingerprintService(cleaningConfig, anchorConfig, segmentConfig);
        this.configFingerprint = fingerprintService.getFingerprint();
    }

    public PipelineResult processTrip(SyntheticTrip trip, RoutingProvider routingProvider) {
        long startNanos = System.nanoTime();

        // 1. GPS Cleaning
        CleaningResult cleaningResult = cleaner.clean(trip.rawPings(), gpsCleaningConfig);

        // 2. Anchor Building
        MilestoneInput endMilestone = trip.milestones().stream()
                .filter(m -> m.type() == MilestoneType.TRIP_END)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trip has no TRIP_END milestone"));

        AnchorResult anchorResult = anchorBuilder.buildAnchors(
                trip.milestones(),
                trip.startTime(),
                trip.endTime(),
                endMilestone.actualLat(),
                endMilestone.actualLon(),
                trip.officePlannedLatitude(),
                trip.officePlannedLongitude(),
                anchorConfig
        );

        // 3. Timeline Merging
        List<TripPoint> timeline = timelineMerger.merge(cleaningResult.cleanedPoints(), anchorResult.anchors());

        // 4. Input Fingerprinting
        String inputHash = TimelineFingerprint.compute(timeline, configFingerprint, DistanceEngine.RULE_VERSION);

        // 5. Distance Engine Calculation
        DistanceResult distanceResult = distanceEngine.calculate(timeline, routingProvider, segmentConfig);

        long pipelineNanos = System.nanoTime() - startNanos;

        int fallbackCount = 0;
        int shortGapCount = 0;
        int parkedCount = 0;
        int routedCount = 0;
        int rejectedCount = 0;

        for (var seg : distanceResult.segments()) {
            switch (seg.rule()) {
                case FALLBACK_AERIAL_1_4 -> fallbackCount++;
                case SHORT_GAP_AERIAL -> shortGapCount++;
                case PARKED -> parkedCount++;
                case ROUTED -> routedCount++;
                case REJECTED_SPEED -> rejectedCount++;
            }
        }

        return new PipelineResult(
                trip.tripIndex(),
                distanceResult.status(),
                distanceResult.totalMeters(),
                distanceResult.providerCalls(),
                distanceResult.cacheHits(),
                distanceResult.cacheMisses(),
                inputHash,
                pipelineNanos,
                fallbackCount,
                shortGapCount,
                parkedCount,
                routedCount,
                rejectedCount
        );
    }
}

