package com.example.gpsengine.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CircuitBreakerTest {

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> currentInstant;
        private final ZoneId zone;

        public MutableClock(Instant initial) {
            this.currentInstant = new AtomicReference<>(initial);
            this.zone = ZoneId.of("UTC");
        }

        public void advance(Duration duration) {
            currentInstant.updateAndGet(inst -> inst.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }

    @Test
    @DisplayName("Circuit stays CLOSED below failure threshold; a success resets failure counter")
    void staysClosedBelowThresholdAndSuccessResets() throws RoutingUnavailableException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30), clock);
        RoutingStats stats = new RoutingStats();

        // 2 failures (threshold = 3)
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> cb.execute(() -> {
                throw new RoutingUnavailableException("Fail");
            }, stats)).isInstanceOf(RoutingUnavailableException.class);
        }

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.consecutiveFailures()).isEqualTo(2);

        // 1 success resets counter
        String result = cb.execute(() -> "success", stats);
        assertThat(result).isEqualTo("success");
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.consecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("Circuit opens at exactly the threshold; calls are rejected with CircuitOpenException without touching provider")
    void opensAtThresholdAndRejectsWithoutTouchingProvider() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30), clock);
        AtomicInteger providerCalls = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> cb.execute(() -> {
                providerCalls.incrementAndGet();
                throw new RoutingUnavailableException("Fail");
            }, null)).isInstanceOf(RoutingUnavailableException.class);
        }

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(providerCalls.get()).isEqualTo(3);

        // Next 5 calls are short-circuited
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cb.execute(() -> {
                providerCalls.incrementAndGet();
                return "unexpected";
            }, null)).isInstanceOf(CircuitOpenException.class);
        }

        // Provider was never invoked for the rejected calls
        assertThat(providerCalls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("After open duration: next call is HALF_OPEN trial; success closes it; failure re-opens it")
    void halfOpenTrialTransitions() throws RoutingUnavailableException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        // Trip the breaker
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RoutingUnavailableException("Fail"); }, null);
            } catch (RoutingUnavailableException ignored) {}
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // 1. Advance clock past open duration (31s) -> transition to HALF_OPEN trial
        clock.advance(Duration.ofSeconds(31));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Failed trial -> re-opens immediately
        assertThatThrownBy(() -> cb.execute(() -> {
            throw new RoutingUnavailableException("Trial failed");
        }, null)).isInstanceOf(RoutingUnavailableException.class);

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2. Advance clock again past open duration
        clock.advance(Duration.ofSeconds(31));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Successful trial -> closes circuit
        String ok = cb.execute(() -> "recovered", null);
        assertThat(ok).isEqualTo("recovered");
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.consecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("Only one trial at a time in HALF_OPEN: concurrent callers during trial are rejected")
    void singleTrialAtATime() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(10), clock);

        // Trip breaker
        try {
            cb.execute(() -> { throw new RoutingUnavailableException("Trip"); }, null);
        } catch (RoutingUnavailableException ignored) {}

        clock.advance(Duration.ofSeconds(15));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        CountDownLatch trialStarted = new CountDownLatch(1);
        CountDownLatch trialRelease = new CountDownLatch(1);
        AtomicReference<Throwable> concurrentError = new AtomicReference<>();

        Thread trialThread = new Thread(() -> {
            try {
                cb.execute(() -> {
                    trialStarted.countDown();
                    try {
                        trialRelease.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "trial-done";
                }, null);
            } catch (Throwable t) {
                // Not expected
            }
        });
        trialThread.start();

        trialStarted.await();

        // While trial is executing in-flight, a second caller must be rejected
        assertThatThrownBy(() -> cb.execute(() -> "second-call", null))
                .isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining("trial call already in progress");

        trialRelease.countDown();
        trialThread.join();

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Non-RoutingUnavailableException errors are not counted as failures and rethrown")
    void runtimeExceptionsNotCountedAsFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(10), clock);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cb.execute(() -> {
                throw new IllegalStateException("Bug in business logic");
            }, null)).isInstanceOf(IllegalStateException.class);
        }

        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.consecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency: many threads hitting open breaker are all rejected without calling provider")
    void concurrentCallsAgainstOpenBreaker() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(60), clock);

        // Trip breaker
        try {
            cb.execute(() -> { throw new RoutingUnavailableException("Trip"); }, null);
        } catch (RoutingUnavailableException ignored) {}

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger rejectionCount = new AtomicInteger(0);
        AtomicInteger providerCalls = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    cb.execute(() -> {
                        providerCalls.incrementAndGet();
                        return "ok";
                    }, null);
                } catch (CircuitOpenException e) {
                    rejectionCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(rejectionCount.get()).isEqualTo(threadCount);
        assertThat(providerCalls.get()).isEqualTo(0);
    }
}

