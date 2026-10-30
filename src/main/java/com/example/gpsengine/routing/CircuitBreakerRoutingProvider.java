package com.example.gpsengine.routing;

import java.util.Objects;

/**
 * Decorator wrapping a downstream RoutingProvider with a CircuitBreaker and telemetry tracking.
 */
public class CircuitBreakerRoutingProvider implements RoutingProvider {

    private final RoutingProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final RoutingStats stats;

    public CircuitBreakerRoutingProvider(RoutingProvider delegate, CircuitBreaker circuitBreaker, RoutingStats stats) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate RoutingProvider must not be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "CircuitBreaker must not be null");
        this.stats = stats;
    }

    @Override
    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingUnavailableException {
        return circuitBreaker.execute(() -> {
            if (stats != null) {
                stats.recordProviderCall();
            }
            return delegate.route(fromLat, fromLon, toLat, toLon);
        }, stats);
    }

    public CircuitBreaker.State state() {
        return circuitBreaker.state();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public RoutingProvider getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "CircuitBreakerRoutingProvider[state=" + circuitBreaker.state() + "]";
    }
}

