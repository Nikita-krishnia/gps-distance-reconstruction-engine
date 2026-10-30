package com.example.gpsengine.routing;

/**
 * Contract for routing providers calculating road distances between two geographic points.
 */
@FunctionalInterface
public interface RoutingProvider {

    /**
     * Queries the routing provider for the road distance between two points.
     *
     * @param fromLat origin latitude
     * @param fromLon origin longitude
     * @param toLat destination latitude
     * @param toLon destination longitude
     * @return RouteResult with road distance in metres
     * @throws RoutingUnavailableException if the provider cannot be reached, times out, or returns invalid data
     */
    RouteResult route(double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingUnavailableException;
}
