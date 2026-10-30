package com.example.gpsengine.routing;

/**
 * Indicates whether a route distance calculation was obtained directly from
 * the upstream provider or resolved via an in-memory cache.
 */
public enum RouteSource {
    PROVIDER,
    CACHE
}

