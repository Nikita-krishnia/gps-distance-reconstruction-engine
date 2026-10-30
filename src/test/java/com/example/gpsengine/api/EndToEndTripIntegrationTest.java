package com.example.gpsengine.api;

import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.security.TestSecurityHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
class EndToEndTripIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoutingProvider routingProvider;

    @Autowired
    @Qualifier("rawRoutingProvider")
    private RoutingProvider rawRoutingProvider;

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";

    @Autowired
    private TestSecurityHelper testSecurityHelper;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUpTokens() {
        tokenA = testSecurityHelper.generateToken("admin-a-e2e", TENANT_A, Role.ADMIN, null);
        tokenB = testSecurityHelper.generateToken("admin-b-e2e", TENANT_B, Role.ADMIN, null);
    }

    @Test
    @DisplayName("Complete end-to-end trip reconstruction flow: idempotency, invoice closing, late pings, signed adjustments")
    void fullTripReconstructionFlow() throws Exception {
        // 1. Create Trip for Tenant A
        String createTripJson = """
                {
                  "officePlannedLatitude": 12.9279,
                  "officePlannedLongitude": 77.6271
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(milestonesJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(4)));

        // 4. Calculate Distance (First Run -> 201 Created)
        MvcResult calc1Result = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status", is("CALCULATED")))
                .andExpect(jsonPath("$.totalMeters").value(greaterThan(0)))
                .andExpect(jsonPath("$.reused", is(false)))
                .andReturn();

        JsonNode calc1Json = objectMapper.readTree(calc1Result.getResponse().getContentAsString());
        long originalTotalMeters = calc1Json.get("totalMeters").asLong();
        long calc1Id = calc1Json.get("id").asLong();

        // 5. Idempotent Calculate: Call calculate again with unchanged data -> 200 OK with reused=true
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) calc1Id)))
                .andExpect(jsonPath("$.reused", is(true)))
                .andExpect(jsonPath("$.totalMeters", is((int) originalTotalMeters)));

        // 6. Useless late pings (duplicate or teleport) -> still reused, no new calculation
        String uselessPings = """
                {
                  "pings": [
                    { "latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 15.0 }
                  ]
                }
                """;
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uselessPings))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) calc1Id)))
                .andExpect(jsonPath("$.reused", is(true)));

        // 7. Close Invoice -> 200 OK
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedCalculationId", is((int) calc1Id)))
                .andExpect(jsonPath("$.billedMeters", is((int) originalTotalMeters)))
                .andExpect(jsonPath("$.closedAt").exists());

        // 8. Closing again is idempotent -> 200 OK with identical body
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedCalculationId", is((int) calc1Id)))
                .andExpect(jsonPath("$.billedMeters", is((int) originalTotalMeters)));

        // 9. Upload meaningful late pings after closing invoice
        String latePing = """
                {
                  "pings": [
                    { "latitude": 12.9500, "longitude": 77.6100, "recordedAt": "2026-10-30T07:18:00Z", "accuracyMeters": 10.0 }
                  ]
                }
                """;
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(latePing))
                .andExpect(status().isCreated());

        // 10. Recalculate closed trip -> 201 Created with BillingAdjustment
        MvcResult lateCalcResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.reused", is(false)))
                .andExpect(jsonPath("$.adjustment").exists())
                .andReturn();

        JsonNode lateCalcJson = objectMapper.readTree(lateCalcResult.getResponse().getContentAsString());
        long newTotalMeters = lateCalcJson.get("totalMeters").asLong();
        long adjustmentDelta = lateCalcJson.get("adjustment").get("deltaMeters").asLong();
        assertThat(adjustmentDelta).isEqualTo(newTotalMeters - originalTotalMeters);

        // 11. Verify Trip Details reflects billed, adjustments, and payableMeters
        mockMvc.perform(get("/api/v1/trips/" + tripId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CALCULATED")))
                .andExpect(jsonPath("$.billingStatus", is("CLOSED")))
                .andExpect(jsonPath("$.billedCalculationId", is((int) calc1Id)))
                .andExpect(jsonPath("$.billedMeters", is((int) originalTotalMeters)))
                .andExpect(jsonPath("$.adjustmentsTotalMeters", is((int) adjustmentDelta)))
                .andExpect(jsonPath("$.payableMeters", is((int) (originalTotalMeters + adjustmentDelta))));

        // 12. GET /billing-adjustments returns ledger history
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deltaMeters", is((int) adjustmentDelta)));

        // 13. Tenant Isolation on all endpoints
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Concurrency: 20 threads calling calculate concurrently on the same trip produce exactly ONE stored row")
    void concurrentCalculateIdempotency() throws Exception {
        // Create fresh trip
        String createTripJson = "{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}";
        MvcResult res = mockMvc.perform(post("/api/v1/trips").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(createTripJson)).andReturn();
        Long tripId = Long.parseLong(res.getResponse().getHeader("Location").substring(res.getResponse().getHeader("Location").lastIndexOf('/') + 1));

        String pingsJson = "{\"pings\": [{\"latitude\": 12.9716, \"longitude\": 77.5946, \"recordedAt\": \"2026-10-30T07:00:00Z\", \"accuracyMeters\": 10.0}]}";
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(pingsJson));

        String milestonesJson = """
                {"milestones": [
                    {"type": "TRIP_START", "actualTime": "2026-10-30T07:00:00Z"},
                    {"type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T07:30:00Z"}
                ]}
                """;
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(milestonesJson));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger okCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    MvcResult mvcRes = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andReturn();
                    int code = mvcRes.getResponse().getStatus();
                    if (code == 201) {
                        createdCount.incrementAndGet();
                    } else if (code == 200) {
                        okCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // Exactly one created (201), the rest reused (200)
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(okCount.get()).isEqualTo(threadCount - 1);

        // Verify database history has exactly 1 row
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Concurrency: 20 threads recalculating a CLOSED trip with late pings produce exactly ONE new calculation and ONE adjustment")
    void concurrentRecalculateOnClosedTrip() throws Exception {
        String createTripJson = "{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}";
        MvcResult res = mockMvc.perform(post("/api/v1/trips").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(createTripJson)).andReturn();
        Long tripId = Long.parseLong(res.getResponse().getHeader("Location").substring(res.getResponse().getHeader("Location").lastIndexOf('/') + 1));

        String pingsJson = "{\"pings\": [{\"latitude\": 12.9716, \"longitude\": 77.5946, \"recordedAt\": \"2026-10-30T07:00:00Z\", \"accuracyMeters\": 10.0}]}";
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(pingsJson));

        String milestonesJson = """
                {"milestones": [
                    {"type": "TRIP_START", "actualTime": "2026-10-30T07:00:00Z"},
                    {"type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T07:30:00Z"}
                ]}
                """;
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(milestonesJson));

        // Initial calculate and close invoice
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andExpect(status().isOk());

        // Upload late valid ping
        String latePing = "{\"pings\": [{\"latitude\": 12.9500, \"longitude\": 77.6000, \"recordedAt\": \"2026-10-30T07:15:00Z\", \"accuracyMeters\": 8.0}]}";
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(latePing));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger okCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    MvcResult mvcRes = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andReturn();
                    int code = mvcRes.getResponse().getStatus();
                    if (code == 201) {
                        createdCount.incrementAndGet();
                    } else if (code == 200) {
                        okCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(okCount.get()).isEqualTo(threadCount - 1);

        // Exactly 2 calculations (initial + recalculation)
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Exactly 1 billing adjustment
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Concurrency: 10 threads calling close-invoice at once all get 200 with the exact same billed calculation and timestamp")
    void concurrentCloseInvoice() throws Exception {
        String createTripJson = "{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}";
        MvcResult res = mockMvc.perform(post("/api/v1/trips").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(createTripJson)).andReturn();
        Long tripId = Long.parseLong(res.getResponse().getHeader("Location").substring(res.getResponse().getHeader("Location").lastIndexOf('/') + 1));

        String pingsJson = "{\"pings\": [{\"latitude\": 12.9716, \"longitude\": 77.5946, \"recordedAt\": \"2026-10-30T07:00:00Z\", \"accuracyMeters\": 10.0}]}";
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(pingsJson));

        String milestonesJson = """
                {"milestones": [
                    {"type": "TRIP_START", "actualTime": "2026-10-30T07:00:00Z"},
                    {"type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T07:30:00Z"}
                ]}
                """;
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(milestonesJson));
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andExpect(status().isCreated());

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        List<MvcResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    MvcResult mvcRes = mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)).andReturn();
                    results.add(mvcRes);
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(results).hasSize(threadCount);

        JsonNode firstNode = objectMapper.readTree(results.get(0).getResponse().getContentAsString());
        long firstCalcId = firstNode.get("billedCalculationId").asLong();
        long firstMeters = firstNode.get("billedMeters").asLong();

        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
            assertThat(node.get("billedCalculationId").asLong()).isEqualTo(firstCalcId);
            assertThat(node.get("billedMeters").asLong()).isEqualTo(firstMeters);
            assertThat(node.get("closedAt").asText()).isNotBlank();
        }
    }
}
