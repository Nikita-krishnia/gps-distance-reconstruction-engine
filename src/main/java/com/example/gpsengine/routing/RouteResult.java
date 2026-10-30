package com.example.gpsengine.routing;

/**
 * Encapsulates the distance returned by an external or mock routing provider.
 *
 * @param distanceMeters route length along road network in metres
 */
public record RouteResult(double distanceMeters) {
}

