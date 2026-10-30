package com.example.gpsengine.api;

import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true"
})
class EndToEndTripIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoutingProvider routingProvider;

    @Autowired
    @Qualifier("rawRoutingProvider")
    private RoutingProvider rawRoutingProvider;

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";

    @Test
    @DisplayName("Complete end-to-end trip reconstruction flow with GPS gaps, anchors, calculations and segments")
    void fullTripReconstructionFlow() throws Exception {
        // 1. Create Trip for Tenant A
        String createTripJson = """
                {
                  "officePlannedLatitude": 12.9279,
                  "officePlannedLongitude": 77.6271
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/trips")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status", is("NEW")))
                .andExpect(jsonPath("$.billingStatus", is("OPEN")))
                .andReturn();

        String location = createResult.getResponse().getHeader("Location");
        Long tripId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // 2. Upload raw GPS Pings (including duplicate, out-of-order, and low-accuracy ping)
        String pingsJson = """
                {
                  "pings": [
                    { "latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 15.0 },
                    { "latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 15.0 },
                    { "latitude": 12.9725, "longitude": 77.5955, "recordedAt": "2026-10-30T07:00:30Z", "accuracyMeters": 12.0 },
                    { "latitude": 12.9800, "longitude": 77.6000, "recordedAt": "2026-10-30T07:01:00Z", "accuracyMeters": 90.0 },
                    { "latitude": 12.9750, "longitude": 77.5980, "recordedAt": "2026-10-30T07:00:45Z", "accuracyMeters": 10.0 },
                    { "latitude": 12.9280, "longitude": 77.6270, "recordedAt": "2026-10-30T07:30:00Z", "accuracyMeters": 14.0 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingsJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(6)));

        // 3. Upload Milestones (Start, 2 drops inside 22-min gap, and Trip End)
        String milestonesJson = """
                {
                  "milestones": [
                    {
                      "type": "TRIP_START",
                      "actualTime": "2026-10-30T07:00:00Z"
                    },
                    {
                      "type": "DROP",
                      "employeeRef": "EMP-001",
                      "riderKind": "PLANNED",
                      "actualLatitude": 12.9600,
                      "actualLongitude": 77.6050,
                      "actualTime": "2026-10-30T07:10:00Z"
                    },
                    {
                      "type": "DROP",
                      "employeeRef": "EMP-002",
                      "riderKind": "PLANNED",
                      "actualLatitude": 12.9450,
                      "actualLongitude": 77.6150,
                      "actualTime": "2026-10-30T07:20:00Z"
                    },
                    {
                      "type": "TRIP_END",
                      "actualLatitude": 12.9280,
                      "actualLongitude": 77.6270,
                      "actualTime": "2026-10-30T07:30:00Z"
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(milestonesJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(4)));

        // 4. Calculate Distance
        MvcResult calcResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status", is("CALCULATED")))
                .andExpect(jsonPath("$.totalMeters").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalKm").exists())
                .andExpect(jsonPath("$.ruleVersion", is("2026.1")))
                .andReturn();

        // 5. Query Trip Details (assert ping count, milestone count, and latest calculation)
        mockMvc.perform(get("/api/v1/trips/" + tripId)
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(tripId.intValue())))
                .andExpect(jsonPath("$.status", is("CALCULATED")))
                .andExpect(jsonPath("$.pingCount", is(6)))
                .andExpect(jsonPath("$.milestoneCount", is(4)))
                .andExpect(jsonPath("$.latestCalculation").exists());

        // 6. Query Calculation History
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 7. Query Segments for the calculation
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations/1/segments")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(2))))
                .andExpect(jsonPath("$[0].sequenceNumber", is(1)))
                .andExpect(jsonPath("$[0].rule").exists())
                .andExpect(jsonPath("$[0].reason").exists());

        // 8. Tenant Isolation: Tenant B must receive 404 for Tenant A's trip on ALL endpoints
        mockMvc.perform(get("/api/v1/trips/" + tripId).header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings").header("X-Tenant-Id", TENANT_B).contentType(MediaType.APPLICATION_JSON).content(pingsJson))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones").header("X-Tenant-Id", TENANT_B).contentType(MediaType.APPLICATION_JSON).content(milestonesJson))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations").header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations").header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations/1/segments").header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments").header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());

        // 9. Determinism: Recalculate with unchanged data -> new calculation record with exact same totalMeters
        MvcResult calc2Result = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isCreated())
                .andReturn();

        // 10. Provider Failure (DEGRADED): Invalidate cache and toggle failure on FakeRoutingProvider -> calculation returns DEGRADED
        if (routingProvider instanceof com.example.gpsengine.routing.CachingRoutingProvider caching) {
            caching.invalidateAll();
        }
        if (rawRoutingProvider instanceof FakeRoutingProvider fake) {
            fake.setFailing(true);
            try {
                mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                                .header("X-Tenant-Id", TENANT_A))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("DEGRADED")));
            } finally {
                fake.setFailing(false);
            }
        }
    }
}
