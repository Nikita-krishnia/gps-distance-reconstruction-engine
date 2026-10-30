package com.example.gpsengine.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Thread-safe circuit breaker tracking upstream failures and isolating degraded routing dependencies.
 *
 * <p>STATES:
 * <ul>
 *   <li><b>CLOSED:</b> Upstream is healthy. Requests are passed through. Consecutive failures are tracked.
 *       When consecutive failures reach the failureThreshold, transition to OPEN.</li>
 *   <li><b>OPEN:</b> Upstream is unhealthy. All requests are short-circuited immediately with
 *       {@link CircuitOpenException} without contacting the provider. Remains OPEN until openDuration passes.</li>
 *   <li><b>HALF_OPEN:</b> Testing recovery. Exactly one trial call is permitted to execute against the
 *       upstream provider. Any other callers during the trial are rejected with {@link CircuitOpenException}.
 *       If trial succeeds -> CLOSED (counter reset). If trial fails -> OPEN with a fresh timer.</li>
 * </ul>
 *
 * <p>SYNCHRONIZATION & CONCURRENCY DESIGN:
 * State checks and transitions are protected by small, localized synchronized blocks on an internal lock object.
 * Critically, <b>no lock is held while the external provider call executes</b>. Holding locks across external I/O
 * would create severe thread contention, block other threads from fast-failing during trials, and cause thread pool
 * starvation under high concurrency.
 */
public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private final Object lock = new Object();
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private Instant openedAt = Instant.EPOCH;
    private boolean trialInFlight = false;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be positive, got: " + failureThreshold);
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Open duration must be positive non-null, got: " + openDuration);
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Functional interface representing a routing operation guarded by the circuit breaker.
     */
    @FunctionalInterface
    public interface RoutingAction<T> {
        T execute() throws RoutingUnavailableException;
    }

    /**
     * Executes the given action through the circuit breaker state machine.
     *
     * @param action the upstream provider call
     * @param stats optional routing stats for recording rejections and failures
     * @param <T> result type
     * @return action result
     * @throws RoutingUnavailableException if provider fails or circuit is open
     */
    public <T> T execute(RoutingAction<T> action, RoutingStats stats) throws RoutingUnavailableException {
        boolean isTrial = preCall(stats);

        try {
            T result = action.execute();
            recordSuccess(isTrial);
            return result;
        } catch (RoutingUnavailableException e) {
            recordFailure(isTrial, stats);
            throw e;
        } catch (Throwable t) {
            // Non-RoutingUnavailableException errors (runtime exceptions, bugs, errors)
            // must NOT count as upstream provider failures and must not trip the breaker.
            abortTrialIfRunning(isTrial);
            throw t;
        }
    }

    private boolean preCall(RoutingStats stats) throws CircuitOpenException {
        synchronized (lock) {
            Instant now = clock.instant();

            if (state == State.OPEN) {
                if (Duration.between(openedAt, now).compareTo(openDuration) >= 0) {
                    // Transition to HALF_OPEN to allow exactly one probe call
                    state = State.HALF_OPEN;
                    trialInFlight = true;
                    return true;
                } else {
                    if (stats != null) {
                        stats.recordCircuitOpenRejection();
                    }
                    throw new CircuitOpenException("Circuit breaker is OPEN; fast-failing request");
                }
            }

            if (state == State.HALF_OPEN) {
                if (!trialInFlight) {
                    trialInFlight = true;
                    return true;
                } else {
                    if (stats != null) {
                        stats.recordCircuitOpenRejection();
                    }
                    throw new CircuitOpenException("Circuit breaker is HALF_OPEN; trial call already in progress");
                }
            }

            // State is CLOSED
            return false;
        }
    }

    private void recordSuccess(boolean isTrial) {
        synchronized (lock) {
            if (isTrial || state == State.HALF_OPEN) {
                state = State.CLOSED;
                trialInFlight = false;
            }
            consecutiveFailures = 0;
        }
    }

    private void recordFailure(boolean isTrial, RoutingStats stats) {
        synchronized (lock) {
            if (stats != null) {
                stats.recordProviderFailure();
            }

            if (isTrial || state == State.HALF_OPEN) {
                // Trial call failed; re-open circuit with a fresh timer
                state = State.OPEN;
                openedAt = clock.instant();
                trialInFlight = false;
                consecutiveFailures++;
                return;
            }

            if (state == State.CLOSED) {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    state = State.OPEN;
                    openedAt = clock.instant();
                }
            }
        }
    }

    private void abortTrialIfRunning(boolean isTrial) {
        if (isTrial) {
            synchronized (lock) {
                trialInFlight = false;
            }
        }
    }

    public State state() {
        synchronized (lock) {
            // Check if open duration has elapsed to reflect logical state accurately
            if (state == State.OPEN && Duration.between(openedAt, clock.instant()).compareTo(openDuration) >= 0) {
                return State.HALF_OPEN;
            }
            return state;
        }
    }

    public boolean isOpen() {
        return state() == State.OPEN;
    }

    public int consecutiveFailures() {
        synchronized (lock) {
            return consecutiveFailures;
        }
    }

    public void reset() {
        synchronized (lock) {
            state = State.CLOSED;
            consecutiveFailures = 0;
            openedAt = Instant.EPOCH;
            trialInFlight = false;
        }
    }
}

