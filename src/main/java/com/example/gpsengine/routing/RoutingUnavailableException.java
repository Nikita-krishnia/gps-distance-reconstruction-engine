package com.example.gpsengine.routing;

/**
 * Checked exception thrown when an external routing provider fails, times out,
 * returns malformed data, or when the circuit breaker is open.
 *
 * A checked exception was selected intentionally to force calling code (e.g. DistanceEngine)
 * to explicitly handle provider failure with the mandated fallback policy (FALLBACK_AERIAL_1_4),
 * ensuring the application never crashes on network failure.
 */
public class RoutingUnavailableException extends Exception {

    public RoutingUnavailableException(String message) {
        super(message);
    }

    public RoutingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

