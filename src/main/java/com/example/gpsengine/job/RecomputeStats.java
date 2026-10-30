package com.example.gpsengine.job;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe statistics tracker for the background degraded trip recomputation job.
 */
public class RecomputeStats {

    private final LongAdder runs = new LongAdder();
    private final LongAdder attempted = new LongAdder();
    private final LongAdder recovered = new LongAdder();
    private final LongAdder stillDegraded = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();
    private volatile java.time.Instant lastSuccessInstant = null;

    public void recordRun() {
        runs.increment();
    }

    public void recordAttempt() {
        attempted.increment();
    }

    public void recordRecovered() {
        recovered.increment();
    }

    public void recordStillDegraded() {
        stillDegraded.increment();
    }

    public void recordSkippedRun() {
        skippedRuns.increment();
    }

    public void recordSuccessRun(java.time.Instant instant) {
        this.lastSuccessInstant = instant;
    }

    public java.time.Instant getLastSuccessInstant() {
        return lastSuccessInstant;
    }

    public Snapshot snapshot() {
        return new Snapshot(runs.sum(), attempted.sum(), recovered.sum(), stillDegraded.sum(), skippedRuns.sum());
    }

    public void reset() {
        runs.reset();
        attempted.reset();
        recovered.reset();
        stillDegraded.reset();
        skippedRuns.reset();
        lastSuccessInstant = null;
    }

    public record Snapshot(
            long runs,
            long attempted,
            long recovered,
            long stillDegraded,
            long skippedRuns
    ) {}
}

