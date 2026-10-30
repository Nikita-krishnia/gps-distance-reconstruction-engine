package com.example.gpsengine.routing;

import com.example.gpsengine.engine.HaversineCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@Tag("unit")
class FakeRoutingProviderTest {

    @Test
    @DisplayName("Output equals straight-line multiplied by detour factor and is deterministic")
    void deterministicOutputWithDetourFactor() throws RoutingUnavailableException {
        double detour = 1.35;
        FakeRoutingProvider provider = new FakeRoutingProvider(detour);

        double fromLat = 12.9716;
        double fromLon = 77.5946;
        double toLat = 12.9352;
        double toLon = 77.6245;

        double straightMeters = HaversineCalculator.distanceMeters(fromLat, fromLon, toLat, toLon);
        double expected = straightMeters * detour;

        RouteResult result1 = provider.route(fromLat, fromLon, toLat, toLon);
        RouteResult result2 = provider.route(fromLat, fromLon, toLat, toLon);

        assertThat(result1.distanceMeters()).isCloseTo(expected, within(0.001));
        assertThat(result2.distanceMeters()).isEqualTo(result1.distanceMeters());
        assertThat(provider.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Routing identical origin and destination points returns 0 distance")
    void samePointTwiceReturnsZero() throws RoutingUnavailableException {
        FakeRoutingProvider provider = new FakeRoutingProvider();

        RouteResult result = provider.route(12.9716, 77.5946, 12.9716, 77.5946);

        assertThat(result.distanceMeters()).isEqualTo(0.0);
        assertThat(provider.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("failAfterCalls succeeds for the first N calls and throws RoutingUnavailableException thereafter")
    void failAfterCallsSimulation() throws RoutingUnavailableException {
        int allowedCalls = 3;
        FakeRoutingProvider provider = new FakeRoutingProvider(1.3, allowedCalls, 0L);

        // First 3 calls succeed
        for (int i = 1; i <= allowedCalls; i++) {
            RouteResult result = provider.route(12.0, 77.0, 12.01, 77.01);
            assertThat(result.distanceMeters()).isGreaterThan(0.0);
            assertThat(provider.getCallCount()).isEqualTo(i);
        }

        // 4th and subsequent calls throw RoutingUnavailableException
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.01, 77.01))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessageContaining("simulated failure after 3 calls");

        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.01, 77.01))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessageContaining("simulated failure after 3 calls");

        assertThat(provider.getCallCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Concurrent execution of 100 calls across multiple threads accurately counts 100 calls")
    void concurrencyCallCounting() throws InterruptedException {
        FakeRoutingProvider provider = new FakeRoutingProvider();
        int totalCalls = 100;
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalCalls);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < totalCalls; i++) {
            final double offset = i * 0.0001;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RouteResult result = provider.route(12.9 + offset, 77.5, 12.91 + offset, 77.51);
                    if (result.distanceMeters() > 0.0) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(totalCalls);
        assertThat(provider.getCallCount()).isEqualTo(totalCalls);
    }
}

