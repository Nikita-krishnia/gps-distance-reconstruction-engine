package com.example.gpsengine.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.domain.AppUser;
import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.DomainFactory;
import com.example.gpsengine.domain.GpsPing;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripMilestone;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.repository.AppUserRepository;
import com.example.gpsengine.repository.DistanceCalculationRepository;
import com.example.gpsengine.repository.GpsPingRepository;
import com.example.gpsengine.repository.TripMilestoneRepository;
import com.example.gpsengine.repository.TripRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        "recompute.enabled=true",
        "recompute.interval-seconds=3600"
})
class AuthenticationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestSecurityHelper testSecurityHelper;

    @Autowired
    private DegradedTripRecomputeJob recomputeJob;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private GpsPingRepository pingRepository;

    @Autowired
    private TripMilestoneRepository milestoneRepository;

    @Autowired
    private DistanceCalculationRepository calculationRepository;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger rootLogger;

    @BeforeEach
    void attachLogAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (rootLogger != null && listAppender != null) {
            rootLogger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("Login success returns access token that authenticates protected trip endpoints")
    void loginSuccessReturnsUsableToken() throws Exception {
        String username = "operator-login-test";
        String password = "CorrectPassword123!";
        testSecurityHelper.createOrGetUser(username, "tenant-login", Role.OPERATOR, null);

        // Update password with explicit known plaintext
        AppUser user = userRepository.findByUsername(username).orElseThrow();
        AppUser updated = DomainFactory.createUser(
                user.getTenantId(), user.getUsername(), passwordEncoder.encode(password),
                user.getRole(), user.getEmployeeRef(), true, user.getCreatedAt()
        );
        userRepository.delete(user);
        userRepository.save(updated);

        String loginBody = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresInSeconds", is(900)))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = jsonNode.get("accessToken").asText();

        // Use minted token on protected POST /api/v1/trips
        mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    @DisplayName("Wrong password, unknown user, and disabled user return IDENTICAL 401 status and error shape")
    void loginFailuresReturnIdenticalResponse() throws Exception {
        String validUser = "existing-user-fail-test";
        String disabledUser = "disabled-user-test";
        String plainPassword = "ValidPassword123!";

        testSecurityHelper.createOrGetUser(validUser, "tenant-fail", Role.OPERATOR, null);
        AppUser u1 = userRepository.findByUsername(validUser).orElseThrow();
        userRepository.delete(u1);
        userRepository.save(DomainFactory.createUser(
                "tenant-fail", validUser, passwordEncoder.encode(plainPassword), Role.OPERATOR, null, true, Instant.now()
        ));

        userRepository.save(DomainFactory.createUser(
                "tenant-fail", disabledUser, passwordEncoder.encode(plainPassword), Role.OPERATOR, null, false, Instant.now()
        ));

        // 1. Wrong password
        MvcResult resWrongPw = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"WrongPassword!\"}", validUser)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, is("Bearer")))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.message", is("Invalid credentials")))
                .andReturn();

        // 2. Unknown user
        MvcResult resUnknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"completely-unknown-user\",\"password\":\"AnyPassword123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, is("Bearer")))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.message", is("Invalid credentials")))
                .andReturn();

        // 3. Disabled user
        MvcResult resDisabled = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", disabledUser, plainPassword)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, is("Bearer")))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.message", is("Invalid credentials")))
                .andReturn();

        JsonNode node1 = objectMapper.readTree(resWrongPw.getResponse().getContentAsString());
        JsonNode node2 = objectMapper.readTree(resUnknown.getResponse().getContentAsString());
        JsonNode node3 = objectMapper.readTree(resDisabled.getResponse().getContentAsString());

        assertThat(node1.get("code").asText()).isEqualTo(node2.get("code").asText()).isEqualTo(node3.get("code").asText());
        assertThat(node1.get("message").asText()).isEqualTo(node2.get("message").asText()).isEqualTo(node3.get("message").asText());
        assertThat(node1.get("status").asInt()).isEqualTo(node2.get("status").asInt()).isEqualTo(node3.get("status").asInt());

        // Privacy verification: assert no passwords or password hashes appear anywhere in the logs
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain(plainPassword);
            assertThat(msg).doesNotContain("$2a$10$");
        }
    }

    @Test
    @DisplayName("Full flow as OPERATOR, then close-invoice as OPERATOR -> 403 and as ADMIN -> 200")
    void fullFlowAsOperatorAndCloseInvoiceAsAdmin() throws Exception {
        String tenant = "tenant-flow";
        String opToken = testSecurityHelper.generateToken("op-flow-user", tenant, Role.OPERATOR, null);
        String adminToken = testSecurityHelper.generateToken("admin-flow-user", tenant, Role.ADMIN, null);

        // 1. Create Trip
        MvcResult createRes = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}"))
                .andExpect(status().isCreated())
                .andReturn();

        String location = createRes.getResponse().getHeader("Location");
        Long tripId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // 2. Upload pings
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "pings": [
                            { "latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 15.0 },
                            { "latitude": 12.9280, "longitude": 77.6270, "recordedAt": "2026-10-30T07:30:00Z", "accuracyMeters": 14.0 }
                          ]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(2)));

        // 3. Upload milestones (TRIP_START and TRIP_END)
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "milestones": [
                            {
                              "type": "TRIP_START",
                              "actualLatitude": 12.9716,
                              "actualLongitude": 77.5946,
                              "actualTime": "2026-10-30T07:00:00Z"
                            },
                            {
                              "type": "TRIP_END",
                              "actualLatitude": 12.9280,
                              "actualLongitude": 77.6270,
                              "actualTime": "2026-10-30T07:30:00Z"
                            }
                          ]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(2)));

        // 4. Calculate
        MvcResult calcRes = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("CALCULATED")))
                .andReturn();

        JsonNode calcJson = objectMapper.readTree(calcRes.getResponse().getContentAsString());
        Long calcId = calcJson.get("id").asLong();

        // 5. GET details, history, segments, adjustments as OPERATOR
        mockMvc.perform(get("/api/v1/trips/" + tripId).header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations/" + calcId + "/segments").header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments").header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isOk());

        // 6. Close invoice as OPERATOR -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        // 7. Close invoice as ADMIN -> 200 OK
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedCalculationId", is(calcId.intValue())));
    }

    @Test
    @DisplayName("Tenant isolation on every endpoint: tenant-b tokens get 404 on tenant-a trips")
    void strictTenantIsolationAcrossAllEndpoints() throws Exception {
        String tokenA = testSecurityHelper.generateToken("op-iso-a", "tenant-iso-a", Role.OPERATOR, null);
        String tokenB = testSecurityHelper.generateToken("op-iso-b", "tenant-iso-b", Role.OPERATOR, null);
        String adminB = testSecurityHelper.generateToken("admin-iso-b", "tenant-iso-b", Role.ADMIN, null);

        // Create trip and calculation under Tenant A
        MvcResult res = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}"))
                .andExpect(status().isCreated())
                .andReturn();

        String loc = res.getResponse().getHeader("Location");
        Long tripAId = Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1));

        // Upload pings and boundaries for Trip A
        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pings\":[{\"latitude\":12.9716,\"longitude\":77.5946,\"recordedAt\":\"2026-10-30T07:00:00Z\"},{\"latitude\":12.9280,\"longitude\":77.6270,\"recordedAt\":\"2026-10-30T07:30:00Z\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/milestones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"milestones\":[{\"type\":\"TRIP_START\",\"actualLatitude\":12.9716,\"actualLongitude\":77.5946,\"actualTime\":\"2026-10-30T07:00:00Z\"},{\"type\":\"TRIP_END\",\"actualLatitude\":12.9280,\"actualLongitude\":77.6270,\"actualTime\":\"2026-10-30T07:30:00Z\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isCreated());

        // Probe trip A using Tenant B Operator -> MUST return 404 on every endpoint
        mockMvc.perform(get("/api/v1/trips/" + tripAId).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));

        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/pings").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pings\":[{\"latitude\":12.0,\"longitude\":77.0,\"recordedAt\":\"2026-10-30T07:00:00Z\"}]}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/milestones").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"milestones\":[{\"type\":\"TRIP_START\",\"actualLatitude\":12.0,\"actualLongitude\":77.0,\"actualTime\":\"2026-10-30T07:00:00Z\"}]}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/trips/" + tripAId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/trips/" + tripAId + "/calculations/1/segments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/trips/" + tripAId + "/billing-adjustments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Probe close-invoice using Tenant B Admin -> MUST return 404, never close another tenant's invoice
        mockMvc.perform(post("/api/v1/trips/" + tripAId + "/close-invoice").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Employee participation rules: reduced summary with exact allowed fields, 404 for unparticipated trips")
    void employeeParticipationAndReducedSummary() throws Exception {
        String tenant = "tenant-emp-test";
        String opToken = testSecurityHelper.generateToken("op-emp-setup", tenant, Role.OPERATOR, null);
        String emp001Token = testSecurityHelper.generateToken("emp-001-user", tenant, Role.EMPLOYEE, "emp-001");
        String emp002Token = testSecurityHelper.generateToken("emp-002-user", tenant, Role.EMPLOYEE, "emp-002");
        String empForeignTenant = testSecurityHelper.generateToken("emp-foreign-user", "tenant-other", Role.EMPLOYEE, "emp-001");

        // Create trip and milestone containing emp-001
        MvcResult res = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officePlannedLatitude\": 12.9279, \"officePlannedLongitude\": 77.6271}"))
                .andExpect(status().isCreated())
                .andReturn();
        String loc = res.getResponse().getHeader("Location");
        Long tripId = Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "pings": [
                            { "latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 15.0 },
                            { "latitude": 12.9280, "longitude": 77.6270, "recordedAt": "2026-10-30T07:30:00Z", "accuracyMeters": 14.0 }
                          ]
                        }
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "milestones": [
                            {
                              "type": "TRIP_START",
                              "actualLatitude": 12.9716,
                              "actualLongitude": 77.5946,
                              "actualTime": "2026-10-30T07:00:00Z"
                            },
                            {
                              "type": "PICKUP",
                              "employeeRef": "emp-001",
                              "actualLatitude": 12.9716,
                              "actualLongitude": 77.5946,
                              "actualTime": "2026-10-30T07:05:00Z"
                            },
                            {
                              "type": "TRIP_END",
                              "actualLatitude": 12.9280,
                              "actualLongitude": 77.6270,
                              "actualTime": "2026-10-30T07:30:00Z"
                            }
                          ]
                        }
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + opToken))
                .andExpect(status().isCreated());

        // 1. Employee-001 reads trip -> 200 OK with reduced summary DTO
        MvcResult empRes = mockMvc.perform(get("/api/v1/trips/" + tripId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + emp001Token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(empRes.getResponse().getContentAsString());
        assertThat(root.has("id")).isTrue();
        assertThat(root.has("status")).isTrue();
        assertThat(root.has("createdAt")).isTrue();
        assertThat(root.has("latestTotalKm")).isTrue();

        // Strict privacy assertion: ensure ONLY the 4 allowed fields exist in the employee response
        int fieldCount = 0;
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            names.next();
            fieldCount++;
        }
        assertThat(fieldCount).isEqualTo(4);

        assertThat(root.has("pingCount")).isFalse();
        assertThat(root.has("milestoneCount")).isFalse();
        assertThat(root.has("billedMeters")).isFalse();
        assertThat(root.has("payableMeters")).isFalse();
        assertThat(root.has("latestCalculation")).isFalse();

        // 2. Employee-002 (not in milestones) reads trip -> 404 NOT FOUND (never 403)
        mockMvc.perform(get("/api/v1/trips/" + tripId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + emp002Token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));

        // 3. Employee in foreign tenant reads trip -> 404 NOT FOUND
        mockMvc.perform(get("/api/v1/trips/" + tripId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + empForeignTenant))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));

        // 4. Employee accessing segments, calculations, adjustments -> 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations").header(HttpHeaders.AUTHORIZATION, "Bearer " + emp001Token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/calculations/1/segments").header(HttpHeaders.AUTHORIZATION, "Bearer " + emp001Token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/trips/" + tripId + "/billing-adjustments").header(HttpHeaders.AUTHORIZATION, "Bearer " + emp001Token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Token for disabled user issued before disabling continues working until expiry (stateless JWT trade-off)")
    void tokenForDisabledUserWorksUntilExpiry() throws Exception {
        String username = "user-will-be-disabled";
        String tenant = "tenant-disable-test";
        AppUser user = testSecurityHelper.createOrGetUser(username, tenant, Role.OPERATOR, null);
        String token = testSecurityHelper.generateToken(user);

        // Disable user in database
        user.setEnabled(false);
        userRepository.save(user);

        // Stateless token verification allows request until token expiry
        mockMvc.perform(get("/api/v1/hello")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Scheduled degraded recompute job runs unauthenticated as a system process")
    void degradedRecomputeJobRunsWithoutAuthentication() {
        String tenant = "tenant-job-test";
        Trip trip = DomainFactory.createTrip(tenant, 12.9279, 77.6271);
        trip.setStatus(TripStatus.DEGRADED);
        Trip savedTrip = tripRepository.save(trip);

        Instant start = Instant.parse("2026-10-30T07:00:00Z");
        Instant end = Instant.parse("2026-10-30T07:30:00Z");

        GpsPing ping1 = DomainFactory.createPing(tenant, savedTrip, 12.9716, 77.5946, start, 15.0);
        GpsPing ping2 = DomainFactory.createPing(tenant, savedTrip, 12.9280, 77.6270, end, 14.0);
        pingRepository.saveAll(List.of(ping1, ping2));

        TripMilestone mStart = DomainFactory.createMilestone(
                tenant, savedTrip, MilestoneType.TRIP_START, null,
                12.9716, 77.5946, start, null, null, null, null, null, null, null
        );
        TripMilestone mEnd = DomainFactory.createMilestone(
                tenant, savedTrip, MilestoneType.TRIP_END, null,
                12.9280, 77.6270, end, null, null, null, null, null, null, null
        );
        milestoneRepository.saveAll(List.of(mStart, mEnd));

        DistanceCalculation degradedCalc = DomainFactory.createCalculation(
                tenant, savedTrip, "dummy-hash", "v1", 5000L, TripStatus.DEGRADED, 1, 0, 1
        );
        calculationRepository.save(degradedCalc);

        // Background system worker runs directly without any active SecurityContext or token
        recomputeJob.runOnce();

        Trip refreshed = tripRepository.findById(savedTrip.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isNotEqualTo(TripStatus.DEGRADED);
    }
}

