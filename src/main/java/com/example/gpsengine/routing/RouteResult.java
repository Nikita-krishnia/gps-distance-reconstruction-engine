package com.example.gpsengine.routing;

/**
 * Encapsulates the distance returned by an external or mock routing provider,
 * along with the origin source (fresh PROVIDER call or CACHE hit).
 *
 * @param distanceMeters route length along road network in metres
 * @param source whether the route was resolved from upstream PROVIDER or in-memory CACHE
 */
public record RouteResult(double distanceMeters, RouteSource source) {

    /**
     * Convenience constructor defaulting origin source to PROVIDER, ensuring backward
     * compatibility with existing code and tests.
     *
     * @param distanceMeters route length along road network in metres
     */
    public RouteResult(double distanceMeters) {
        this(distanceMeters, RouteSource.PROVIDER);
    }
}
