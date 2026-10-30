package com.example.gpsengine.job;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe statistics tracker for the background degraded trip recomputation job.
 */
public class RecomputeStats {

    private final LongAdder attempted = new LongAdder();
    private final LongAdder recovered = new LongAdder();
    private final LongAdder stillDegraded = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();

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

    public Snapshot snapshot() {
        return new Snapshot(attempted.sum(), recovered.sum(), stillDegraded.sum(), skippedRuns.sum());
    }

    public void reset() {
        attempted.reset();
        recovered.reset();
        stillDegraded.reset();
        skippedRuns.reset();
    }

    public record Snapshot(
            long attempted,
            long recovered,
            long stillDegraded,
            long skippedRuns
    ) {}
}

