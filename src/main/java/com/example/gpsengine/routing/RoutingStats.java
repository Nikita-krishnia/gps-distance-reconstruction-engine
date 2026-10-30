package com.example.gpsengine.routing;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe routing metrics aggregator tracking cache operations, upstream provider
 * invocations, upstream failures, and circuit breaker rejections.
 */
public class RoutingStats {

    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheBypasses = new LongAdder();
    private final LongAdder providerCalls = new LongAdder();
    private final LongAdder providerFailures = new LongAdder();
    private final LongAdder circuitOpenRejections = new LongAdder();

    public void recordHit() {
        cacheHits.increment();
    }

    public void recordMiss() {
        cacheMisses.increment();
    }

    public void recordBypass() {
        cacheBypasses.increment();
    }

    public void recordProviderCall() {
        providerCalls.increment();
    }

    public void recordProviderFailure() {
        providerFailures.increment();
    }

    public void recordCircuitOpenRejection() {
        circuitOpenRejections.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                cacheHits.sum(),
                cacheMisses.sum(),
                cacheBypasses.sum(),
                providerCalls.sum(),
                providerFailures.sum(),
                circuitOpenRejections.sum()
        );
    }

    public void reset() {
        cacheHits.reset();
        cacheMisses.reset();
        cacheBypasses.reset();
        providerCalls.reset();
        providerFailures.reset();
        circuitOpenRejections.reset();
    }

    public record Snapshot(
            long cacheHits,
            long cacheMisses,
            long cacheBypasses,
            long providerCalls,
            long providerFailures,
            long circuitOpenRejections
    ) {}
}

