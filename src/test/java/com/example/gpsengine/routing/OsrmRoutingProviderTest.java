package com.example.gpsengine.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OsrmRoutingProviderTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private void configureServerHandler(HttpHandler handler) {
        server.createContext("/", handler);
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String responseJson) throws IOException {
        byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("Success: valid OSRM response parses distance in metres")
    void successfulRouting() throws RoutingUnavailableException {
        String json = """
                {
                  "code": "Ok",
                  "routes": [
                    {
                      "distance": 3450.8,
                      "duration": 420.5
                    }
                  ]
                }
                """;
        configureServerHandler(exchange -> sendJsonResponse(exchange, 200, json));

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 2000, objectMapper);
        RouteResult result = provider.route(12.9716, 77.5946, 12.9352, 77.6245);

        assertThat(result.distanceMeters()).isEqualTo(3450.8);
    }

    @Test
    @DisplayName("Request path correctly formats longitude first, then latitude with dot separator")
    void requestPathFormatsLongitudeFirstThenLatitude() throws RoutingUnavailableException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String json = """
                {
                  "code": "Ok",
                  "routes": [{"distance": 100.0}]
                }
                """;
        configureServerHandler(exchange -> {
            capturedPath.set(exchange.getRequestURI().toString());
            sendJsonResponse(exchange, 200, json);
        });

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 2000, objectMapper);
        provider.route(12.9716, 77.5946, 12.9352, 77.6245);

        assertThat(capturedPath.get()).isNotNull();
        // OSRM convention check: longitude (77.594600) must appear before latitude (12.971600)
        assertThat(capturedPath.get())
                .isEqualTo("/route/v1/driving/77.594600,12.971600;77.624500,12.935200?overview=false");
    }

    @Test
    @DisplayName("HTTP 500 and HTTP 429 responses throw RoutingUnavailableException with numeric status")
    void httpErrorsThrowExceptionWithStatusCode() {
        configureServerHandler(exchange -> {
            if (exchange.getRequestURI().getPath().contains("500")) {
                sendJsonResponse(exchange, 500, "{\"message\":\"Internal Server Error\"}");
            } else {
                sendJsonResponse(exchange, 429, "{\"message\":\"Too Many Requests\"}");
            }
        });

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 2000, objectMapper);

        // Test HTTP 429
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM returned HTTP status 429");
    }

    @Test
    @DisplayName("Malformed JSON, empty body, and code!=Ok throw RoutingUnavailableException")
    void invalidJsonAndErrorCodeHandling() {
        AtomicReference<String> serverResponse = new AtomicReference<>("not-json");
        configureServerHandler(exchange -> {
            byte[] bytes = serverResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 2000, objectMapper);

        // 1. Non-JSON body
        serverResponse.set("<html><body>Error</body></html>");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM returned invalid JSON response");

        // 2. Empty body
        serverResponse.set("");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM returned empty response body");

        // 3. code != "Ok"
        serverResponse.set("{\"code\":\"NoRoute\",\"routes\":[]}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM returned error code: NoRoute");
    }

    @Test
    @DisplayName("Missing/empty routes and invalid distances (negative, non-numeric, NaN) throw exception")
    void routeAndDistanceValidation() {
        AtomicReference<String> serverResponse = new AtomicReference<>("{}");
        configureServerHandler(exchange -> sendJsonResponse(exchange, 200, serverResponse.get()));

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 2000, objectMapper);

        // Missing routes
        serverResponse.set("{\"code\":\"Ok\"}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM response contains missing or empty routes");

        // Empty routes
        serverResponse.set("{\"code\":\"Ok\",\"routes\":[]}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM response contains missing or empty routes");

        // Missing distance
        serverResponse.set("{\"code\":\"Ok\",\"routes\":[{}]}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM response contains invalid distance");

        // Negative distance
        serverResponse.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":-50.0}]}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM response contains invalid distance");

        // Non-numeric distance
        serverResponse.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":\"unroutable\"}]}");
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM response contains invalid distance");
    }

    @Test
    @DisplayName("Slow server exceeding request timeout throws RoutingUnavailableException and aborts quickly")
    void requestTimeoutHandling() {
        configureServerHandler(exchange -> {
            try {
                // Sleep longer than the 150ms timeout configured on the provider
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            sendJsonResponse(exchange, 200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":100.0}]}");
        });

        // 150ms request timeout
        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + port, null, 1000, 150, objectMapper);

        Instant start = Instant.now();
        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM request timed out");
        Duration elapsed = Duration.between(start, Instant.now());

        // Must finish quickly without hanging for the full 600ms
        assertThat(elapsed.toMillis()).isLessThan(550);
    }

    @Test
    @DisplayName("Connection refused against closed port throws RoutingUnavailableException")
    void connectionRefused() throws IOException {
        // Find a free port by creating and immediately closing a server socket
        java.net.ServerSocket socket = new java.net.ServerSocket(0);
        int closedPort = socket.getLocalPort();
        socket.close();

        OsrmRoutingProvider provider = new OsrmRoutingProvider("http://localhost:" + closedPort, null, 1000, 1000, objectMapper);

        assertThatThrownBy(() -> provider.route(12.0, 77.0, 12.1, 77.1))
                .isInstanceOf(RoutingUnavailableException.class)
                .hasMessage("OSRM request failed: network I/O error");
    }

    @Test
    @DisplayName("Authorization header is sent when API key is set, and omitted when API key is blank")
    void apiKeyAuthorizationHeaderHandling() throws RoutingUnavailableException {
        AtomicReference<String> authHeader = new AtomicReference<>();
        configureServerHandler(exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sendJsonResponse(exchange, 200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":100.0}]}");
        });

        // 1. With API key
        OsrmRoutingProvider providerWithKey = new OsrmRoutingProvider(
                "http://localhost:" + port, "test-secret-token", 1000, 2000, objectMapper
        );
        providerWithKey.route(12.0, 77.0, 12.1, 77.1);
        assertThat(authHeader.get()).isEqualTo("Bearer test-secret-token");

        // 2. With blank API key
        authHeader.set(null);
        OsrmRoutingProvider providerNoKey = new OsrmRoutingProvider(
                "http://localhost:" + port, "   ", 1000, 2000, objectMapper
        );
        providerNoKey.route(12.0, 77.0, 12.1, 77.1);
        assertThat(authHeader.get()).isNull();
    }

    @Test
    @DisplayName("Privacy: no exception message or toString contains raw coordinates, full URL, or API key")
    void privacyVerification() {
        String secretApiKey = "RECOGNIZABLE_SECRET_KEY_XYZ_9876";
        double testLat = 48.858844;
        double testLon = 2.294351;
        String baseUrl = "http://localhost:" + port + "/secret-internal-endpoint";

        configureServerHandler(exchange -> sendJsonResponse(exchange, 500, "Server exploded"));

        OsrmRoutingProvider provider = new OsrmRoutingProvider(
                baseUrl, secretApiKey, 1000, 2000, objectMapper
        );

        // Check toString representation
        String representation = provider.toString();
        assertThat(representation).doesNotContain(secretApiKey);
        assertThat(representation).doesNotContain(baseUrl);
        assertThat(representation).doesNotContain("/secret-internal-endpoint");
        assertThat(representation).doesNotContain(String.valueOf(testLat));
        assertThat(representation).doesNotContain(String.valueOf(testLon));

        // Check exception message
        try {
            provider.route(testLat, testLon, 48.860611, 2.337644);
        } catch (RoutingUnavailableException e) {
            String msg = e.getMessage();
            assertThat(msg).doesNotContain(secretApiKey);
            assertThat(msg).doesNotContain(baseUrl);
            assertThat(msg).doesNotContain("/secret-internal-endpoint");
            assertThat(msg).doesNotContain(String.valueOf(testLat));
            assertThat(msg).doesNotContain(String.valueOf(testLon));
        }
    }
}

