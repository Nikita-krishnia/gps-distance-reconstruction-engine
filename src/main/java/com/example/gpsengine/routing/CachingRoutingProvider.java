package com.example.gpsengine.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Decorator wrapping a downstream {@link RoutingProvider} with an in-memory Caffeine cache.
 *
 * <p>ARCHITECTURE & AVAILABILITY:
 * The decorator chain is structured as:
 * {@code DistanceEngine -> CachingRoutingProvider -> CircuitBreakerRoutingProvider -> raw provider}.
 * Because cache lookups precede the circuit breaker, cache hits return immediately without touching
 * the circuit breaker. If the upstream provider experiences an outage and the circuit breaker trips OPEN,
 * frequently requested corridors and routes continue to be served seamlessly from cache.
 *
 * <p>DETERMINISM RULE:
 * On a cache miss, the wrapped provider is called with the <b>CENTRE of the start cell</b> and the
 * <b>CENTRE of the end cell</b>, rather than the caller's raw continuous coordinates.
 * Consequently, every coordinate pair mapping to the same start/end grid cells generates the exact same
 * provider query and yields the exact same cached distance. The stored result never depends on which thread
 * or request arrived first. This eliminates race conditions and ensures 100% deterministic reconstruction.
 *
 * <p>SAME-CELL RULE:
 * If the start and end coordinates fall within the exact same grid cell, caching is bypassed. Calling the
 * provider with identical cell centres would yield 0 metres of displacement. Therefore, same-cell movements
 * invoke the wrapped provider with the caller's exact coordinates, and the result is intentionally not stored
 * in the cache. This is tracked in {@link RoutingStats#recordBypass()}.
 *
 * <p>BOUNDED STALENESS & MEMORY:
 * <ul>
 *   <li><b>Staleness:</b> Bounded strictly by {@code expireAfterWrite} (TTL). After the TTL elapses, entries
 *       expire and are re-fetched. Invalidation also occurs whenever the {@code providerVersion} changes.</li>
 *   <li><b>Memory:</b> Bounded strictly by {@code maximumSize}. Excess entries are evicted using Caffeine's
 *       Window TinyLFU eviction policy.</li>
 *   <li><b>Position Accuracy:</b> Bounded strictly by {@code cellSizeDegrees}. For example, a 0.001 degree
 *       cell is approximately 111 metres on Earth, displacing endpoints by at most ~78 metres from the centre.</li>
 * </ul>
 *
 * <p>DATA PRIVACY:
 * Cache keys, raw coordinates, and API credentials are never logged or exposed via {@code toString()}.
 */
public class CachingRoutingProvider implements RoutingProvider {

    private final RoutingProvider delegate;
    private final double cellSizeDegrees;
    private final String providerVersion;
    private final Cache<RouteCacheKey, Double> cache;
    private final RoutingStats stats;

    /**
     * Internal cache key record. Directional (fromCell -> toCell) to respect one-way streets.
     * Never printed or logged.
     */
    record RouteCacheKey(
            long fromLatCell,
            long fromLonCell,
            long toLatCell,
            long toLonCell,
            String providerVersion
    ) {}

    public CachingRoutingProvider(
            RoutingProvider delegate,
            long maximumSize,
            Duration ttl,
            double cellSizeDegrees,
            String providerVersion,
            RoutingStats stats) {
        this(delegate, maximumSize, ttl, cellSizeDegrees, providerVersion, stats, Ticker.systemTicker());
    }

    public CachingRoutingProvider(
            RoutingProvider delegate,
            long maximumSize,
            Duration ttl,
            double cellSizeDegrees,
            String providerVersion,
            RoutingStats stats,
            Ticker ticker) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("Maximum cache size must be positive, got: " + maximumSize);
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Cache TTL must be positive non-null, got: " + ttl);
        }
        if (cellSizeDegrees <= 0.0) {
            throw new IllegalArgumentException("Cell size degrees must be positive, got: " + cellSizeDegrees);
        }
        if (providerVersion == null || providerVersion.isBlank()) {
            throw new IllegalArgumentException("Provider version must not be blank");
        }

        this.delegate = Objects.requireNonNull(delegate, "Delegate RoutingProvider must not be null");
        this.cellSizeDegrees = cellSizeDegrees;
        this.providerVersion = providerVersion.trim();
        this.stats = stats;

        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl);
        if (ticker != null) {
            builder.ticker(ticker);
        }
        this.cache = builder.build();
    }

    @Override
    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingUnavailableException {
        long fromLatCell = snapToCellIndex(fromLat);
        long fromLonCell = snapToCellIndex(fromLon);
        long toLatCell = snapToCellIndex(toLat);
        long toLonCell = snapToCellIndex(toLon);

        // SAME-CELL RULE:
        // When start and end fall in the same grid cell, do NOT cache.
        // Centre-to-centre routing would evaluate to 0m displacement.
        // Forward exact caller coordinates directly to delegate.
        if (fromLatCell == toLatCell && fromLonCell == toLonCell) {
            if (stats != null) {
                stats.recordBypass();
            }
            RouteResult directResult = delegate.route(fromLat, fromLon, toLat, toLon);
            return new RouteResult(directResult.distanceMeters(), RouteSource.PROVIDER);
        }

        RouteCacheKey key = new RouteCacheKey(
                fromLatCell, fromLonCell, toLatCell, toLonCell, providerVersion
        );

        // Track whether this thread executed the loader (cache miss) or retrieved an existing entry (cache hit).
        boolean[] isMiss = new boolean[]{false};

        try {
            Double distance = cache.get(key, k -> {
                isMiss[0] = true;
                double centerFromLat = cellCenterCoordinate(k.fromLatCell());
                double centerFromLon = cellCenterCoordinate(k.fromLonCell());
                double centerToLat = cellCenterCoordinate(k.toLatCell());
                double centerToLon = cellCenterCoordinate(k.toLonCell());

                try {
                    RouteResult raw = delegate.route(centerFromLat, centerFromLon, centerToLat, centerToLon);
                    return raw.distanceMeters();
                } catch (RoutingUnavailableException e) {
                    // Wrap checked exception to bubble out of Caffeine loader without caching failure
                    throw new CompletionException(e);
                }
            });

            if (distance == null) {
                throw new RoutingUnavailableException("Routing cache returned null distance");
            }

            if (isMiss[0]) {
                if (stats != null) {
                    stats.recordMiss();
                }
                return new RouteResult(distance, RouteSource.PROVIDER);
            } else {
                if (stats != null) {
                    stats.recordHit();
                }
                return new RouteResult(distance, RouteSource.CACHE);
            }
        } catch (CompletionException e) {
            if (e.getCause() instanceof RoutingUnavailableException rue) {
                throw rue;
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RoutingUnavailableException("Failed to load route distance from provider", e.getCause());
        }
    }

    private long snapToCellIndex(double coord) {
        return (long) Math.floor(coord / cellSizeDegrees);
    }

    private double cellCenterCoordinate(long cellIndex) {
        return (cellIndex + 0.5) * cellSizeDegrees;
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public void cleanUp() {
        cache.cleanUp();
    }

    public long size() {
        return cache.estimatedSize();
    }

    public RoutingProvider getDelegate() {
        return delegate;
    }

    public String getProviderVersion() {
        return providerVersion;
    }

    public double getCellSizeDegrees() {
        return cellSizeDegrees;
    }

    @Override
    public String toString() {
        return "CachingRoutingProvider[providerVersion=" + providerVersion
                + ", cellSizeDegrees=" + cellSizeDegrees
                + ", estimatedSize=" + cache.estimatedSize() + "]";
    }
}

