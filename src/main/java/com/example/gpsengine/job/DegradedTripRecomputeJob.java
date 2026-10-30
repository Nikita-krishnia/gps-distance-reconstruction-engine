package com.example.gpsengine.job;

import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripDataPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled background worker that detects DEGRADED trips and recalculates them
 * automatically once the upstream routing provider recovers.
 *
 * <p>SINGLE-INSTANCE EFFICIENCY ASSUMPTION:
 * The job is optimized for single-instance execution. Under multi-node clusters, concurrent runs
 * are completely data-safe (due to PostgreSQL row locks and unique constraints on DistanceCalculation),
 * but could issue redundant routing provider calls. Distributed leader election or
 * SELECT ... FOR UPDATE SKIP LOCKED is intentionally omitted for simplicity.
 */
@Component
@ConditionalOnProperty(name = "recompute.enabled", havingValue = "true", matchIfMissing = true)
public class DegradedTripRecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(DegradedTripRecomputeJob.class);

    private final TripDataPersistenceService persistenceService;
    private final CalculationService calculationService;
    private final CircuitBreaker circuitBreaker;
    private final RecomputeStats stats;
    private final int batchSize;

    public DegradedTripRecomputeJob(
            TripDataPersistenceService persistenceService,
            CalculationService calculationService,
            CircuitBreaker circuitBreaker,
            RecomputeStats stats,
            @Value("${recompute.batch-size:50}") int batchSize) {
        this.persistenceService = persistenceService;
        this.calculationService = calculationService;
        this.circuitBreaker = circuitBreaker;
        this.stats = stats;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${recompute.interval-seconds:30}000")
    public void schedule() {
        runOnce();
    }

    public void runOnce() {
        // Skip run immediately if circuit breaker is OPEN
        if (circuitBreaker.isOpen()) {
            stats.recordSkippedRun();
            log.info("Degraded recompute job skipped: circuit breaker is currently OPEN");
            return;
        }

        List<Trip> degradedTrips = persistenceService.findDegradedTrips(batchSize);
        if (degradedTrips.isEmpty()) {
            return;
        }

        long attempted = 0;
        long recovered = 0;
        long stillDegraded = 0;

        for (Trip trip : degradedTrips) {
            attempted++;
            stats.recordAttempt();

            try {
                CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(
                        trip.getId(), trip.getTenantId()
                );

                if (response.status() == TripStatus.CALCULATED) {
                    recovered++;
                    stats.recordRecovered();
                } else if (response.status() == TripStatus.DEGRADED) {
                    stillDegraded++;
                    stats.recordStillDegraded();
                    // Provider is still unhealthy; stop current batch to avoid hammering upstream
                    log.warn("Recomputation yielded DEGRADED status for trip {}; stopping recompute batch early", trip.getId());
                    break;
                }
            } catch (Exception ex) {
                log.error("Exception during background recomputation for trip {}: {}", trip.getId(), ex.getClass().getSimpleName());
            }
        }

        log.info("Degraded recompute run completed [attempted={}] [recovered={}] [stillDegraded={}]",
                attempted, recovered, stillDegraded);
    }
}

