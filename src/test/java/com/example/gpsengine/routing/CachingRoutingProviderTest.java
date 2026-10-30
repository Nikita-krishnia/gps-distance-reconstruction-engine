package com.example.gpsengine.routing;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CachingRoutingProviderTest {

    @Test
    @DisplayName("Same request twice calls wrapped provider once; second result has source CACHE")
    void sameRequestTwiceReturnsCached() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        RouteResult r1 = caching.route(12.9716, 77.5946, 12.9352, 77.6245);
        RouteResult r2 = caching.route(12.9716, 77.5946, 12.9352, 77.6245);

        assertThat(r1.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(r2.source()).isEqualTo(RouteSource.CACHE);
        assertThat(r2.distanceMeters()).isEqualTo(r1.distanceMeters());
        assertThat(raw.getCallCount()).isEqualTo(1);

        RoutingStats.Snapshot snap = stats.snapshot();
        assertThat(snap.cacheMisses()).isEqualTo(1);
        assertThat(snap.cacheHits()).isEqualTo(1);
        assertThat(snap.cacheBypasses()).isEqualTo(0);
    }

    @Test
    @DisplayName("Different points inside same start/end cells call provider once (snapping works) with same distance")
    void pointsInSameCellReturnSameCachedDistance() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        RoutingStats stats = new RoutingStats();
        // Cell size = 0.001 degrees (~110m). Points within 0.0003 snap to same cell
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        // Point A1 -> B1
        RouteResult r1 = caching.route(12.0002, 77.0002, 12.0102, 77.0102);
        // Point A2 -> B2 (both within respective start/end cells [12000, 77000] and [12010, 77010])
        RouteResult r2 = caching.route(12.0008, 77.0008, 12.0108, 77.0108);

        assertThat(raw.getCallCount()).isEqualTo(1);
        assertThat(r1.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(r2.source()).isEqualTo(RouteSource.CACHE);
        assertThat(r2.distanceMeters()).isEqualTo(r1.distanceMeters());
    }

    @Test
    @DisplayName("Determinism: request order A-then-B vs B-then-A gives identical cached distances for each key")
    void determinismOrderIndependent() throws RoutingUnavailableException {
        // Cache 1: query Pair 1 first, then Pair 2
        FakeRoutingProvider raw1 = new FakeRoutingProvider(1.3);
        CachingRoutingProvider c1 = new CachingRoutingProvider(raw1, 1000, Duration.ofHours(1), 0.001, "v1", null);
        RouteResult p1_c1 = c1.route(12.0002, 77.0002, 12.0102, 77.0102);
        RouteResult p2_c1 = c1.route(12.0202, 77.0202, 12.0302, 77.0302);

        // Cache 2: query Pair 2 first, then Pair 1
        FakeRoutingProvider raw2 = new FakeRoutingProvider(1.3);
        CachingRoutingProvider c2 = new CachingRoutingProvider(raw2, 1000, Duration.ofHours(1), 0.001, "v1", null);
        RouteResult p2_c2 = c2.route(12.0202, 77.0202, 12.0302, 77.0302);
        RouteResult p1_c2 = c2.route(12.0002, 77.0002, 12.0102, 77.0102);

        // Distances for Pair 1 and Pair 2 match across caches regardless of arrival order
        assertThat(p1_c1.distanceMeters()).isEqualTo(p1_c2.distanceMeters());
        assertThat(p2_c1.distanceMeters()).isEqualTo(p2_c2.distanceMeters());
    }

    @Test
    @DisplayName("Reverse direction is a separate key and triggers separate provider call")
    void reverseDirectionSeparateKey() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", null
        );

        // Forward: A -> B
        RouteResult forward = caching.route(12.0002, 77.0002, 12.0502, 77.0502);
        // Reverse: B -> A
        RouteResult reverse = caching.route(12.0502, 77.0502, 12.0002, 77.0002);

        assertThat(raw.getCallCount()).isEqualTo(2);
        assertThat(forward.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(reverse.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(caching.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Same-cell request bypasses cache: provider receives exact coordinates and nothing is cached")
    void sameCellBypassesCache() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        // Both points lie inside the same cell [12000, 77000]
        RouteResult r1 = caching.route(12.0002, 77.0002, 12.0006, 77.0006);
        RouteResult r2 = caching.route(12.0002, 77.0002, 12.0006, 77.0006);

        // Because it bypasses cache, both calls reach the provider directly
        assertThat(raw.getCallCount()).isEqualTo(2);
        assertThat(r1.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(r2.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(caching.size()).isEqualTo(0);
        assertThat(stats.snapshot().cacheBypasses()).isEqualTo(2);
        assertThat(stats.snapshot().cacheHits()).isEqualTo(0);
    }

    @Test
    @DisplayName("Failures are not cached: after an exception, a later call reaches provider again")
    void failuresAreNotCached() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", null
        );

        // Make provider fail
        raw.setFailing(true);

        assertThatThrownBy(() -> caching.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class);
        assertThat(caching.size()).isEqualTo(0);

        // Recover provider
        raw.setFailing(false);

        RouteResult recovered = caching.route(12.0, 77.0, 12.1, 77.1);
        assertThat(recovered.distanceMeters()).isGreaterThan(0.0);
        assertThat(recovered.source()).isEqualTo(RouteSource.PROVIDER);
        assertThat(caching.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Entry expiry via Caffeine Ticker forces fresh provider call after TTL")
    void entryExpiryViaTicker() throws RoutingUnavailableException {
        AtomicLong fakeNanos = new AtomicLong(0L);
        Ticker testTicker = fakeNanos::get;

        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        Duration ttl = Duration.ofSeconds(60);
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, ttl, 0.001, "v1", null, testTicker
        );

        caching.route(12.0, 77.0, 12.1, 77.1);
        assertThat(raw.getCallCount()).isEqualTo(1);

        // Advance simulated time past TTL
        fakeNanos.addAndGet(Duration.ofSeconds(61).toNanos());

        caching.route(12.0, 77.0, 12.1, 77.1);
        assertThat(raw.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidateAll() and changed providerVersion both force new provider calls")
    void invalidationAndVersionChange() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        CachingRoutingProvider c1 = new CachingRoutingProvider(raw, 1000, Duration.ofHours(1), 0.001, "v1", null);

        c1.route(12.0, 77.0, 12.1, 77.1);
        assertThat(raw.getCallCount()).isEqualTo(1);

        // Invalidate all
        c1.invalidateAll();
        c1.route(12.0, 77.0, 12.1, 77.1);
        assertThat(raw.getCallCount()).isEqualTo(2);

        // New provider version "v2"
        CachingRoutingProvider c2 = new CachingRoutingProvider(raw, 1000, Duration.ofHours(1), 0.001, "v2", null);
        c2.route(12.0, 77.0, 12.1, 77.1);
        assertThat(raw.getCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Max size evicts entries and keeps cache within bounds")
    void maxSizeEviction() throws RoutingUnavailableException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3);
        long maxSize = 5L;
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, maxSize, Duration.ofHours(1), 0.001, "v1", null
        );

        for (int i = 0; i < 20; i++) {
            caching.route(12.0 + i * 0.01, 77.0, 12.1 + i * 0.01, 77.1);
        }

        // Run Caffeine maintenance cleanup to enforce maximumSize bounds synchronously
        caching.cleanUp();
        assertThat(caching.size()).isLessThanOrEqualTo(maxSize);
    }

    @Test
    @DisplayName("Concurrency: 50 threads requesting the same key simultaneously call provider exactly once")
    void concurrentRequestsSingleProviderCall() throws InterruptedException {
        FakeRoutingProvider raw = new FakeRoutingProvider(1.3, -1, 20L); // 20ms simulated latency
        RoutingStats stats = new RoutingStats();
        CachingRoutingProvider caching = new CachingRoutingProvider(
                raw, 1000, Duration.ofHours(1), 0.001, "v1", stats
        );

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    RouteResult res = caching.route(12.0002, 77.0002, 12.0502, 77.0502);
                    if (res.distanceMeters() > 0.0) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = endGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(raw.getCallCount()).isEqualTo(1);
        assertThat(stats.snapshot().cacheMisses()).isEqualTo(1);
        assertThat(stats.snapshot().cacheHits()).isEqualTo(threadCount - 1);
    }
}

