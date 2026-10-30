package com.example.gpsengine.monitoring;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.CleaningResult;
import com.example.gpsengine.job.RecomputeStats;
import com.example.gpsengine.repository.TripRepository;
import com.example.gpsengine.routing.CachingRoutingProvider;
import com.example.gpsengine.routing.CircuitBreaker;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central metrics recorder for the GPS Distance Reconstruction Engine.
 *
 * <p>Exposes low-cardinality, domain-tailored Micrometer metrics under the 'gps.' namespace.
 * Guarantees privacy: never uses tripId, tenantId, username, employeeRef, coordinates,
 * or sensitive hashes as metric tag values.
 */
@Component
public class GpsMetrics {

    private final MeterRegistry registry;
    private final TripRepository tripRepository;
    private final Clock clock;
    private final RecomputeStats recomputeStats;
    private final RoutingStats routingStats;
    private final CircuitBreaker circuitBreaker;
    private final ObjectProvider<RoutingProvider> routingProviderProvider;

    // Cached calculation timers and counters
    private final Timer calculationDurationTimer;
    private final Map<String, Counter> calculationCounters = new ConcurrentHashMap<>();
    private final Map<SegmentRule, Counter> segmentRuleCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> pingsRemovedCounters = new ConcurrentHashMap<>();

    // Billing & concurrency
    private final Counter billingAdjustmentsCounter;
    private final DistributionSummary billingAdjustmentDeltaSummary;
    private final Counter concurrencyConflictsCounter;
    private final Counter invoicesClosedCounter;

    // Security & Errors
    private final Counter authLoginSuccessCounter;
    private final Counter authLoginFailureCounter;
    private final Counter authDenied401Counter;
    private final Counter authDenied403Counter;
    private final Counter unexpectedErrorsCounter;

    // Degraded trips gauge snapshot cache (refreshed at most once every 15 seconds)
    private Instant lastDegradedSnapshotTime = Instant.EPOCH;
    private long cachedDegradedTripCount = 0;
    private double cachedOldestDegradedAgeSeconds = 0.0;
    private final AtomicLong dbSnapshotQueryCount = new AtomicLong(0);

    public GpsMetrics(
            MeterRegistry registry,
            TripRepository tripRepository,
            Clock clock,
            RecomputeStats recomputeStats,
            RoutingStats routingStats,
            CircuitBreaker circuitBreaker,
            ObjectProvider<RoutingProvider> routingProviderProvider) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.tripRepository = Objects.requireNonNull(tripRepository, "tripRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.recomputeStats = Objects.requireNonNull(recomputeStats, "recomputeStats must not be null");
        this.routingStats = Objects.requireNonNull(routingStats, "routingStats must not be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        this.routingProviderProvider = routingProviderProvider;

        // 1. Calculations: Duration Timer (percentile histogram OFF to minimize memory and time series cardinality)
        this.calculationDurationTimer = Timer.builder("gps.calculation.duration")
                .description("Execution duration of distance calculation pipeline")
                .publishPercentileHistogram(false)
                .register(registry);

        // Pre-register standard calculation status counters
        for (String status : new String[]{"calculated", "degraded", "reused", "failed"}) {
            calculationCounters.put(status, Counter.builder("gps.calculations")
                    .tag("status", status)
                    .register(registry));
        }

        // Pre-register segment rule counters
        for (SegmentRule rule : SegmentRule.values()) {
            segmentRuleCounters.put(rule, Counter.builder("gps.calculation.segments")
                    .tag("rule", rule.name())
                    .register(registry));
        }

        // Pre-register dropped pings reason counters
        for (String reason : new String[]{"invalid", "duplicate", "low_accuracy", "teleport"}) {
            pingsRemovedCounters.put(reason, Counter.builder("gps.calculation.pings.removed")
                    .tag("reason", reason)
                    .register(registry));
        }

        // 2. Routing metrics: bind to existing RoutingStats and CircuitBreaker
        FunctionCounter.builder("gps.routing.provider.calls", routingStats, s -> s.snapshot().providerCalls())
                .description("Total external routing provider invocations")
                .register(registry);

        FunctionCounter.builder("gps.routing.provider.failures", routingStats, s -> s.snapshot().providerFailures())
                .description("Total external routing provider failures")
                .register(registry);

        FunctionCounter.builder("gps.routing.cache", routingStats, s -> s.snapshot().cacheHits())
                .tag("result", "hit")
                .register(registry);

        FunctionCounter.builder("gps.routing.cache", routingStats, s -> s.snapshot().cacheMisses())
                .tag("result", "miss")
                .register(registry);

        FunctionCounter.builder("gps.routing.cache", routingStats, s -> s.snapshot().cacheBypasses())
                .tag("result", "bypass")
                .register(registry);

        FunctionCounter.builder("gps.routing.breaker.rejections", routingStats, s -> s.snapshot().circuitOpenRejections())
                .description("Total calls rejected while circuit breaker was OPEN")
                .register(registry);

        Gauge.builder("gps.routing.breaker.state", circuitBreaker, cb -> {
            CircuitBreaker.State s = cb.state();
            return s == CircuitBreaker.State.CLOSED ? 0 : (s == CircuitBreaker.State.HALF_OPEN ? 1 : 2);
        }).description("Circuit breaker state: 0=CLOSED, 1=HALF_OPEN, 2=OPEN").register(registry);

        Gauge.builder("gps.routing.cache.size", this, GpsMetrics::getRoutingCacheSize)
                .description("Current number of cached road route segments")
                .register(registry);

        // Bind transition listener to circuit breaker
        circuitBreaker.setTransitionListener(this::recordBreakerTransition);

        // 3. Recompute job metrics: bind to RecomputeStats
        FunctionCounter.builder("gps.recompute.runs", recomputeStats, s -> s.snapshot().runs())
                .register(registry);
        FunctionCounter.builder("gps.recompute.attempted", recomputeStats, s -> s.snapshot().attempted())
                .register(registry);
        FunctionCounter.builder("gps.recompute.recovered", recomputeStats, s -> s.snapshot().recovered())
                .register(registry);
        FunctionCounter.builder("gps.recompute.still_degraded", recomputeStats, s -> s.snapshot().stillDegraded())
                .register(registry);
        FunctionCounter.builder("gps.recompute.skipped", recomputeStats, s -> s.snapshot().skippedRuns())
                .register(registry);

        // Degraded trips gauges (cached snapshot <= 15s)
        Gauge.builder("gps.degraded.trips", this, GpsMetrics::getDegradedTripsCount)
                .description("Current count of trips in DEGRADED status (queue depth)")
                .register(registry);

        Gauge.builder("gps.degraded.oldest.age.seconds", this, GpsMetrics::getOldestDegradedAgeSeconds)
                .description("Age of the oldest DEGRADED trip in seconds (recompute lag)")
                .register(registry);

        Gauge.builder("gps.recompute.last.success.age.seconds", this, GpsMetrics::getLastRecomputeSuccessAgeSeconds)
                .description("Seconds since the last recompute run that completed without leaving degraded trips")
                .register(registry);

        // 4. Billing and concurrency
        this.billingAdjustmentsCounter = Counter.builder("gps.billing.adjustments")
                .description("Total ledgered billing adjustments created")
                .register(registry);

        this.billingAdjustmentDeltaSummary = DistributionSummary.builder("gps.billing.adjustments.delta.abs.meters")
                .description("Absolute meter magnitude of billing adjustments")
                .baseUnit("meters")
                .register(registry);

        this.concurrencyConflictsCounter = Counter.builder("gps.concurrency.conflicts")
                .description("Total 409 CONCURRENT_MODIFICATION errors returned")
                .register(registry);

        this.invoicesClosedCounter = Counter.builder("gps.invoices.closed")
                .description("Total invoices successfully closed")
                .register(registry);

        // 5. Security & Errors
        this.authLoginSuccessCounter = Counter.builder("gps.auth.logins")
                .tag("result", "success")
                .register(registry);

        this.authLoginFailureCounter = Counter.builder("gps.auth.logins")
                .tag("result", "failure")
                .register(registry);

        this.authDenied401Counter = Counter.builder("gps.auth.denied")
                .tag("type", "401")
                .register(registry);

        this.authDenied403Counter = Counter.builder("gps.auth.denied")
                .tag("type", "403")
                .register(registry);

        this.unexpectedErrorsCounter = Counter.builder("gps.errors.unexpected")
                .description("Total 500 unexpected internal errors handled")
                .register(registry);
    }

    // --- Calculation Metric Methods ---

    public void recordCalculation(String status, Duration duration) {
        Counter counter = calculationCounters.get(status);
        if (counter != null) {
            counter.increment();
        }
        if (duration != null) {
            calculationDurationTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordCalculationFailed() {
        Counter counter = calculationCounters.get("failed");
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordSegments(Map<SegmentRule, Integer> ruleCounts) {
        if (ruleCounts == null || ruleCounts.isEmpty()) {
            return;
        }
        for (Map.Entry<SegmentRule, Integer> entry : ruleCounts.entrySet()) {
            Counter counter = segmentRuleCounters.get(entry.getKey());
            if (counter != null && entry.getValue() != null && entry.getValue() > 0) {
                counter.increment(entry.getValue());
            }
        }
    }

    public void recordPingsRemoved(CleaningResult result) {
        if (result == null) {
            return;
        }
        if (result.invalidCoordinates() > 0) {
            Counter c = pingsRemovedCounters.get("invalid");
            if (c != null) c.increment(result.invalidCoordinates());
        }
        if (result.duplicates() > 0) {
            Counter c = pingsRemovedCounters.get("duplicate");
            if (c != null) c.increment(result.duplicates());
        }
        if (result.lowAccuracy() > 0) {
            Counter c = pingsRemovedCounters.get("low_accuracy");
            if (c != null) c.increment(result.lowAccuracy());
        }
        if (result.teleports() > 0) {
            Counter c = pingsRemovedCounters.get("teleport");
            if (c != null) c.increment(result.teleports());
        }
    }

    // --- Breaker Transitions ---

    public void recordBreakerTransition(CircuitBreaker.State from, CircuitBreaker.State to) {
        registry.counter("gps.routing.breaker.transitions",
                "from", from.name().toLowerCase(),
                "to", to.name().toLowerCase()).increment();
    }

    // --- Billing, Concurrency & Security ---

    public void recordBillingAdjustment(long absDeltaMeters) {
        billingAdjustmentsCounter.increment();
        billingAdjustmentDeltaSummary.record(absDeltaMeters);
    }

    public void recordInvoiceClosed() {
        invoicesClosedCounter.increment();
    }

    public void recordConcurrencyConflict() {
        concurrencyConflictsCounter.increment();
    }

    public void recordAuthLogin(boolean success) {
        if (success) {
            authLoginSuccessCounter.increment();
        } else {
            authLoginFailureCounter.increment();
        }
    }

    public void recordAuthDenied(String type) {
        if ("401".equals(type)) {
            authDenied401Counter.increment();
        } else if ("403".equals(type)) {
            authDenied403Counter.increment();
        }
    }

    public void recordUnexpectedError() {
        unexpectedErrorsCounter.increment();
    }

    // --- Gauges Implementation ---

    private double getRoutingCacheSize() {
        try {
            RoutingProvider provider = routingProviderProvider.getIfAvailable();
            if (provider instanceof CachingRoutingProvider cachingProvider) {
                return cachingProvider.size();
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private synchronized void refreshDegradedSnapshotIfStale() {
        Instant now = clock.instant();
        if (Duration.between(lastDegradedSnapshotTime, now).toSeconds() >= 15) {
            dbSnapshotQueryCount.incrementAndGet();
            cachedDegradedTripCount = tripRepository.countByStatus(TripStatus.DEGRADED);
            Optional<Instant> oldest = tripRepository.findOldestCreatedAtByStatus(TripStatus.DEGRADED);
            if (oldest.isPresent()) {
                cachedOldestDegradedAgeSeconds = Math.max(0.0, Duration.between(oldest.get(), now).toMillis() / 1000.0);
            } else {
                cachedOldestDegradedAgeSeconds = 0.0;
            }
            lastDegradedSnapshotTime = now;
        }
    }

    public double getDegradedTripsCount() {
        refreshDegradedSnapshotIfStale();
        return cachedDegradedTripCount;
    }

    public double getOldestDegradedAgeSeconds() {
        refreshDegradedSnapshotIfStale();
        return cachedOldestDegradedAgeSeconds;
    }

    public double getLastRecomputeSuccessAgeSeconds() {
        Instant lastSuccess = recomputeStats.getLastSuccessInstant();
        if (lastSuccess == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(lastSuccess, clock.instant()).toMillis() / 1000.0);
    }

    public long getDbSnapshotQueryCount() {
        return dbSnapshotQueryCount.get();
    }
}

