package com.example.gpsengine.routing;

import com.example.gpsengine.engine.HaversineCalculator;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory routing provider for automated tests, local development, and benchmarks.
 *
 * It calculates road distance as the straight-line Haversine distance multiplied by a configurable
 * detour factor (default 1.3). The same coordinate pair always produces identical output.
 *
 * Supports optional simulated failure injection (failing after a given call count) and latency
 * simulation for fault-tolerance and circuit-breaker verification.
 */
public class FakeRoutingProvider implements RoutingProvider {

    public static final double DEFAULT_DETOUR_FACTOR = 1.3;

    private final double detourFactor;
    private final int failAfterCalls;
    private final long latencyMillis;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(false);

    public FakeRoutingProvider() {
        this(DEFAULT_DETOUR_FACTOR, -1, 0L);
    }

    public FakeRoutingProvider(double detourFactor) {
        this(detourFactor, -1, 0L);
    }

    public FakeRoutingProvider(double detourFactor, int failAfterCalls, long latencyMillis) {
        if (detourFactor < 1.0) {
            throw new IllegalArgumentException("Detour factor must be at least 1.0, got: " + detourFactor);
        }
        this.detourFactor = detourFactor;
        this.failAfterCalls = failAfterCalls;
        this.latencyMillis = Math.max(0L, latencyMillis);
    }

    @Override
    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingUnavailableException {
        int currentCall = callCount.incrementAndGet();

        // Runtime failure toggling
        if (failing.get()) {
            throw new RoutingUnavailableException("Fake routing provider simulated failure via setFailing(true)");
        }

        // Failure injection: if failAfterCalls is configured (>= 0), succeed for the first N calls,
        // and throw RoutingUnavailableException for all subsequent calls.
        if (failAfterCalls >= 0 && currentCall > failAfterCalls) {
            throw new RoutingUnavailableException("Fake routing provider simulated failure after " + failAfterCalls + " calls");
        }

        // Latency simulation
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException e) {
                // Restore interrupt status so calling thread/executor is aware of interruption
                Thread.currentThread().interrupt();
                throw new RoutingUnavailableException("Fake routing provider simulated latency interrupted");
            }
        }

        if (fromLat == toLat && fromLon == toLon) {
            return new RouteResult(0.0);
        }

        double straightMeters = HaversineCalculator.distanceMeters(fromLat, fromLon, toLat, toLon);
        return new RouteResult(straightMeters * detourFactor);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void setFailing(boolean failing) {
        this.failing.set(failing);
    }

    public boolean isFailing() {
        return failing.get();
    }

    public double getDetourFactor() {
        return detourFactor;
    }

    public int getFailAfterCalls() {
        return failAfterCalls;
    }

    public long getLatencyMillis() {
        return latencyMillis;
    }

    @Override
    public String toString() {
        return "FakeRoutingProvider[detourFactor=" + detourFactor
                + ", failAfterCalls=" + failAfterCalls
                + ", latencyMillis=" + latencyMillis
                + ", calls=" + callCount.get() + "]";
    }
}

