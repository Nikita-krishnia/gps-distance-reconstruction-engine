package com.example.gpsengine.routing;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.engine.DistanceEngine;
import com.example.gpsengine.engine.DistanceResult;
import com.example.gpsengine.engine.HaversineCalculator;
import com.example.gpsengine.engine.PointOrigin;
import com.example.gpsengine.engine.SegmentConfig;
import com.example.gpsengine.engine.TripPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class RoutingEngineIntegrationTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DistanceEngine engine = new DistanceEngine();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void configureServerHandler(String responseJson, int statusCode) {
        server.createContext("/", (HttpExchange exchange) -> {
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    @DisplayName("DistanceEngine with OsrmRoutingProvider records ROUTED segment and CALCULATED status on success")
    void routedSegmentOnSuccessfulOsrmResponse() {
        String json = """
                {
                  "code": "Ok",
                  "routes": [
                    {
                      "distance": 1250.0,
                      "duration": 100.0
                    }
                  ]
                }
                """;
        configureServerHandler(json, 200);

        OsrmRoutingProvider osrmProvider = new OsrmRoutingProvider(
                "http://localhost:" + port, null, 1000, 2000, objectMapper
        );

        // Gap of 120 seconds between points with ~930m distance (~28 km/h speed, triggers Rule 4 ROUTED)
        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(120);

        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9800, 77.5946, t1, PointOrigin.GPS, null);

        DistanceResult result = engine.calculate(List.of(p1, p2), osrmProvider, SegmentConfig.defaults());

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(result.segments().get(0).meters()).isEqualTo(1250L);
        assertThat(result.totalMeters()).isEqualTo(1250L);
        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
        assertThat(result.providerCalls()).isEqualTo(1);
        assertThat(result.ruleCounts().get(SegmentRule.ROUTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("DistanceEngine falls back to FALLBACK_AERIAL_1_4 and marks status DEGRADED when OSRM fails")
    void fallbackAndDegradedWhenOsrmFailsMidRun() {
        // Stop server immediately to simulate provider downtime / connection refused
        server.stop(0);

        OsrmRoutingProvider osrmProvider = new OsrmRoutingProvider(
                "http://localhost:" + port, null, 1000, 1000, objectMapper
        );

        Instant t0 = Instant.parse("2026-10-30T07:00:00Z");
        Instant t1 = t0.plusSeconds(120);

        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9800, 77.5946, t1, PointOrigin.GPS, null);

        double straightMeters = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.9800, 77.5946);
        long expectedFallbackMeters = Math.round(straightMeters * 1.4);

        DistanceResult result = engine.calculate(List.of(p1, p2), osrmProvider, SegmentConfig.defaults());

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.FALLBACK_AERIAL_1_4);
        assertThat(result.segments().get(0).meters()).isEqualTo(expectedFallbackMeters);
        assertThat(result.totalMeters()).isEqualTo(expectedFallbackMeters);
        assertThat(result.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(result.providerCalls()).isEqualTo(1);
        assertThat(result.ruleCounts().get(SegmentRule.FALLBACK_AERIAL_1_4)).isEqualTo(1);
    }
}

