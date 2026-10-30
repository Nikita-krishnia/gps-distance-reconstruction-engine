package com.example.gpsengine.routing;

/**
 * Exception thrown when the circuit breaker is in the OPEN state (or trial in-flight in HALF_OPEN)
 * and rejects an incoming routing request immediately without attempting an upstream call.
 *
 * <p>Extends {@link RoutingUnavailableException} so that downstream components (like {@code DistanceEngine})
 * treat breaker rejections uniformly by applying fallback heuristics (e.g. straight-line aerial x 1.4)
 * and flagging the calculation as {@code DEGRADED}.
 */
public class CircuitOpenException extends RoutingUnavailableException {

    public CircuitOpenException(String message) {
        super(message);
    }
}

