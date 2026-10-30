package com.example.gpsengine.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional smoke test that executes against a real, live OSRM instance.
 *
 * <p>This test is disabled by default during standard test runs and CI builds to prevent
 * external network flakiness and public demo server rate-limiting. It runs ONLY when
 * the environment variable OSRM_LIVE_TEST=true is explicitly provided.
 */
@EnabledIfEnvironmentVariable(named = "OSRM_LIVE_TEST", matches = "true")
@Tag("integration")
class OsrmLiveSmokeTest {

    @Test
    @DisplayName("Live smoke test against real OSRM instance")
    void liveOsrmSmokeTest() throws RoutingUnavailableException {
        String liveBaseUrl = System.getenv("OSRM_BASE_URL");
        if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
            liveBaseUrl = "https://router.project-osrm.org";
        }
        String apiKey = System.getenv("OSRM_API_KEY");

        OsrmRoutingProvider provider = new OsrmRoutingProvider(liveBaseUrl, apiKey, 5000, 5000, null);

        // Routing between two points in Berlin
        RouteResult result = provider.route(52.517037, 13.388860, 52.529407, 13.397634);

        assertThat(result).isNotNull();
        assertThat(result.distanceMeters()).isGreaterThan(500.0);
    }
}

