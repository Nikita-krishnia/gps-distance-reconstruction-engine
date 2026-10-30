package com.example.gpsengine.routing;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * RoutingProvider implementation backed by an external OSRM (Open Source Routing Machine)
 * or OSRM-compatible HTTP server.
 *
 * <p>COORDINATE ORDER CONVENTION:
 * OSRM expects coordinates in {longitude},{latitude} order (X, Y in standard Cartesian mapping),
 * which is the opposite of the common human convention of {latitude},{longitude}. This provider
 * explicitly formats query paths as {fromLon},{fromLat};{toLon},{toLat}.
 *
 * <p>SECURITY & PRIVACY:
 * Full request URLs, raw coordinates, and API keys are strictly excluded from exception messages,
 * logs, and toString() representations.
 */
public class OsrmRoutingProvider implements RoutingProvider {

    private static final Logger log = LoggerFactory.getLogger(OsrmRoutingProvider.class);

    private final String baseUrl;
    private final String host;
    private final String apiKey;
    private final int requestTimeoutMillis;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OsrmRoutingProvider(String baseUrl) {
        this(baseUrl, null, 2000, 3000, new ObjectMapper());
    }

    public OsrmRoutingProvider(String baseUrl, int connectTimeoutMillis, int requestTimeoutMillis) {
        this(baseUrl, null, connectTimeoutMillis, requestTimeoutMillis, new ObjectMapper());
    }

    public OsrmRoutingProvider(String baseUrl, String apiKey, int connectTimeoutMillis, int requestTimeoutMillis, ObjectMapper objectMapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OSRM base URL must not be blank");
        }

        String trimmed = baseUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("OSRM base URL must start with http:// or https://, got: " + trimmed);
        }

        // Strip trailing slashes to prevent double-slash formatting issues
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;

        URI uri;
        try {
            uri = URI.create(this.baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OSRM base URL is malformed", e);
        }

        this.host = uri.getHost() != null ? uri.getHost() : "unknown-host";

        // Security check: warn if unencrypted HTTP is used for remote (non-localhost) destinations
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            boolean isLocal = "localhost".equalsIgnoreCase(this.host)
                    || "127.0.0.1".equals(this.host)
                    || "::1".equals(this.host);
            if (!isLocal) {
                // Log host only, never full URL path, credentials, or query parameters
                log.warn("OSRM routing provider configured with unencrypted HTTP for remote host: {}", this.host);
            }
        }

        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
        this.requestTimeoutMillis = Math.max(1, requestTimeoutMillis);
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();

        int connectTimeout = Math.max(1, connectTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    /**
     * Package-private constructor allowing injection of a custom or preconfigured HttpClient
     * for specialized testing scenarios.
     */
    OsrmRoutingProvider(String baseUrl, String apiKey, int requestTimeoutMillis, HttpClient httpClient, ObjectMapper objectMapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OSRM base URL must not be blank");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        URI uri = URI.create(this.baseUrl);
        this.host = uri.getHost() != null ? uri.getHost() : "unknown-host";
        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
        this.requestTimeoutMillis = Math.max(1, requestTimeoutMillis);
        this.httpClient = httpClient;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingUnavailableException {
        // IMPORTANT: OSRM expects {longitude},{latitude} ordering!
        // We use Locale.ROOT so coordinates are formatted with a dot decimal point, never a comma.
        String url = String.format(Locale.ROOT, "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false",
                baseUrl, fromLon, fromLat, toLon, toLat);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(requestTimeoutMillis))
                .header("Accept", "application/json")
                .GET();

        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new RoutingUnavailableException("OSRM request timed out");
        } catch (IOException e) {
            throw new RoutingUnavailableException("OSRM request failed: network I/O error");
        } catch (InterruptedException e) {
            // When a thread is interrupted while waiting for an HTTP response, Java clears the interrupted flag.
            // We restore the flag by calling Thread.currentThread().interrupt() so higher-level code
            // (such as thread pools or shutdown tasks) knows the thread was interrupted, and then throw
            // RoutingUnavailableException so the engine can safely fall back to aerial distance.
            Thread.currentThread().interrupt();
            throw new RoutingUnavailableException("OSRM request was interrupted");
        }

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            throw new RoutingUnavailableException("OSRM returned HTTP status " + statusCode);
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new RoutingUnavailableException("OSRM returned empty response body");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new RoutingUnavailableException("OSRM returned invalid JSON response");
        }

        if (root == null || !root.isObject()) {
            throw new RoutingUnavailableException("OSRM returned invalid JSON response");
        }

        JsonNode codeNode = root.get("code");
        if (codeNode == null || !"Ok".equalsIgnoreCase(codeNode.asText())) {
            String codeText = (codeNode != null && !codeNode.asText().isBlank()) ? codeNode.asText() : "Unknown";
            throw new RoutingUnavailableException("OSRM returned error code: " + codeText);
        }

        JsonNode routesNode = root.get("routes");
        if (routesNode == null || !routesNode.isArray() || routesNode.isEmpty()) {
            throw new RoutingUnavailableException("OSRM response contains missing or empty routes");
        }

        JsonNode firstRoute = routesNode.get(0);
        if (firstRoute == null || !firstRoute.isObject()) {
            throw new RoutingUnavailableException("OSRM response contains missing or empty routes");
        }

        JsonNode distanceNode = firstRoute.get("distance");
        if (distanceNode == null || !distanceNode.isNumber()) {
            throw new RoutingUnavailableException("OSRM response contains invalid distance");
        }

        double distance = distanceNode.asDouble();
        if (Double.isNaN(distance) || Double.isInfinite(distance) || distance < 0.0) {
            throw new RoutingUnavailableException("OSRM response contains invalid distance");
        }

        return new RouteResult(distance);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    @Override
    public String toString() {
        return "OsrmRoutingProvider[host=" + host + ", hasApiKey=" + (apiKey != null) + "]";
    }
}

