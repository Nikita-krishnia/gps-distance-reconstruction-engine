package com.example.gpsengine.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable, thread-safe test clock for deterministic time manipulation in unit & integration tests.
 */
public class TestClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    public TestClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = new AtomicReference<>(initialInstant);
        this.zone = zone;
    }

    public TestClock(Instant initialInstant) {
        this(initialInstant, ZoneOffset.UTC);
    }

    public static TestClock fixed(Instant instant) {
        return new TestClock(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }

    public void set(Instant newInstant) {
        currentInstant.set(newInstant);
    }

    public void advance(Duration duration) {
        currentInstant.updateAndGet(i -> i.plus(duration));
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }
}

