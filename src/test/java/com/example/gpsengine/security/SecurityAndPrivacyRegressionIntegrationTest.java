package com.example.gpsengine.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.example.gpsengine.api.dto.AuthDto;
import com.example.gpsengine.domain.Role;
import com.example.gpsengine.job.DegradedTripRecomputeJob;
import com.example.gpsengine.testutil.InMemoryLogAppender;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "routing.provider=fake",
        "recompute.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
public class SecurityAndPrivacyRegressionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestSecurityHelper securityHelper;

    @Autowired
    private DegradedTripRecomputeJob recomputeJob;

    private InMemoryLogAppender logAppender;
    private Logger rootLogger;
    private Level originalLevel;

    private static final String TENANT = "tenant-privacy-reg";
    private static final String SENSITIVE_PASSWORD = "secret-password-123";
    private static final String SENSITIVE_EMPLOYEE_REF = "emp-secret-xyz-99";

    @BeforeEach
    void setupLogAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
        logAppender = new InMemoryLogAppender();
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            logAppender.close();
        }
        if (rootLogger != null) {
            rootLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("H: Full trip lifecycle and error regressions strictly adhere to security, privacy, and error shape standards")
    void fullLifecycleAndPrivacyVerification() throws Exception {
        List<String> responseBodies = new ArrayList<>();

        // 1. Create admin user and login
        securityHelper.createOrGetUser("privacy-admin", TENANT, Role.ADMIN, null);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDto.LoginRequest("privacy-admin", SENSITIVE_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginResult.getResponse().getContentAsString();
        responseBodies.add(loginBody);
        AuthDto.LoginResponse loginResp = objectMapper.readValue(loginBody, AuthDto.LoginResponse.class);
        String token = loginResp.accessToken();
        String bearer = "Bearer " + token;

        // 2. Create Trip (POST /api/v1/trips)
        String tripJsonStr = """
                {
                  "officePlannedLatitude": 12.9279,
                  "officePlannedLongitude": 77.6271
                }
                """;
        MvcResult tripResult = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJsonStr))
                .andExpect(status().isCreated())
                .andReturn();
        String tripBody = tripResult.getResponse().getContentAsString();
        responseBodies.add(tripBody);
        JsonNode tripJson = objectMapper.readTree(tripBody);
        Long tripId = tripJson.get("id").asLong();

        // 3. Upload Milestones
        String milestonesJson = """
                {
                  "milestones": [
                    { "type": "TRIP_START", "actualTime": "2026-10-30T10:00:00Z" },
                    { "type": "DROP", "employeeRef": "%s", "actualLatitude": 12.9700, "actualLongitude": 77.5900, "actualTime": "2026-10-30T10:05:00Z" },
                    { "type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T10:30:00Z" }
                  ]
                }
                """.formatted(SENSITIVE_EMPLOYEE_REF);
        MvcResult msResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/milestones")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(milestonesJson))
                .andExpect(status().isCreated())
                .andReturn();
        responseBodies.add(msResult.getResponse().getContentAsString());

        // 4. Upload Pings
        String pingsJson = """
                {
                  "pings": [
                    { "latitude": 12.9700, "longitude": 77.5900, "recordedAt": "2026-10-30T10:01:00Z", "accuracyMeters": 10.0 },
                    { "latitude": 12.9500, "longitude": 77.6000, "recordedAt": "2026-10-30T10:10:00Z", "accuracyMeters": 10.0 },
                    { "latitude": 12.9300, "longitude": 77.6200, "recordedAt": "2026-10-30T10:20:00Z", "accuracyMeters": 10.0 }
                  ]
                }
                """;
        MvcResult pingsResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingsJson))
                .andExpect(status().isCreated())
                .andReturn();
        responseBodies.add(pingsResult.getResponse().getContentAsString());

        // 5. Calculate Distance
        MvcResult calcResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated())
                .andReturn();
        responseBodies.add(calcResult.getResponse().getContentAsString());

        // 6. Close Invoice
        MvcResult closeResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn();
        responseBodies.add(closeResult.getResponse().getContentAsString());

        // 7. Add Late Pings & Recalculate (Adjustment on closed trip)
        String latePingsJson = """
                {
                  "pings": [
                    { "latitude": 12.9400, "longitude": 77.6100, "recordedAt": "2026-10-30T10:15:00Z", "accuracyMeters": 10.0 }
                  ]
                }
                """;
        MvcResult latePingsResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(latePingsJson))
                .andExpect(status().isCreated())
                .andReturn();
        responseBodies.add(latePingsResult.getResponse().getContentAsString());

        MvcResult recalcResult = mockMvc.perform(post("/api/v1/trips/" + tripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated())
                .andReturn();
        responseBodies.add(recalcResult.getResponse().getContentAsString());

        // 8. Run Scheduled Job
        recomputeJob.runOnce();

        // 9. Error Status Regressions: 400, 401, 403, 404, 409, 422
        // 400 Bad Request: Malformed JSON
        MvcResult err400 = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertStandardErrorShape(err400.getResponse().getContentAsString(), 400);

        // 401 Unauthorized: Missing Authorization header
        MvcResult err401 = mockMvc.perform(get("/api/v1/trips/" + tripId))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertStandardErrorShape(err401.getResponse().getContentAsString(), 401);

        // 403 Forbidden: Employee user attempting admin/operator-only action (create trip)
        securityHelper.createOrGetUser("privacy-emp", TENANT, Role.EMPLOYEE, SENSITIVE_EMPLOYEE_REF);
        MvcResult empLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDto.LoginRequest("privacy-emp", SENSITIVE_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        AuthDto.LoginResponse empLoginResp = objectMapper.readValue(empLogin.getResponse().getContentAsString(), AuthDto.LoginResponse.class);
        String empBearer = "Bearer " + empLoginResp.accessToken();

        MvcResult err403 = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, empBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJsonStr))
                .andExpect(status().isForbidden())
                .andReturn();
        assertStandardErrorShape(err403.getResponse().getContentAsString(), 403);

        // 404 Not Found: Access non-existent trip id
        MvcResult err404 = mockMvc.perform(get("/api/v1/trips/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andReturn();
        assertStandardErrorShape(err404.getResponse().getContentAsString(), 404);

        // 409 Conflict: Calling close-invoice on dirty trip (data changed after calculation)
        MvcResult dirtyTripResult = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJsonStr))
                .andExpect(status().isCreated())
                .andReturn();
        Long dirtyTripId = objectMapper.readTree(dirtyTripResult.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(post("/api/v1/trips/" + dirtyTripId + "/milestones")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(milestonesJson))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/trips/" + dirtyTripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingsJson))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/trips/" + dirtyTripId + "/calculations")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated());
        // Add late ping without recalculation to create dirty data condition
        mockMvc.perform(post("/api/v1/trips/" + dirtyTripId + "/pings")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(latePingsJson))
                .andExpect(status().isCreated());
        MvcResult err409 = mockMvc.perform(post("/api/v1/trips/" + dirtyTripId + "/close-invoice")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andReturn();
        assertStandardErrorShape(err409.getResponse().getContentAsString(), 409);

        // 400 / 422: Out of range coordinates
        String invalidCoordJson = """
                {
                  "officePlannedLatitude": 95.0,
                  "officePlannedLongitude": 77.6271
                }
                """;
        MvcResult err422 = mockMvc.perform(post("/api/v1/trips")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidCoordJson))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertStandardErrorShape(err422.getResponse().getContentAsString(), 400);

        // -------------------------------------------------------------
        // RIGOROUS PRIVACY & LOG VERIFICATION ACROSS ENTIRE TEST RUN
        // -------------------------------------------------------------
        String allLogs = logAppender.getAllLogsJoined();

        // A. No password, password hashes, JWT secrets or tokens in any log
        assertThat(allLogs)
                .as("Raw password must never be logged")
                .doesNotContain(SENSITIVE_PASSWORD);
        assertThat(allLogs)
                .as("BCrypt password hash must never be logged")
                .doesNotContain("$2a$");
        assertThat(allLogs)
                .as("Raw JWT token must never be logged")
                .doesNotContain(token);
        assertThat(allLogs)
                .as("Bearer token header must never be logged")
                .doesNotContain(bearer);

        // B. No employee references appear in any log message
        assertThat(allLogs)
                .as("Sensitive employee ref must never be logged")
                .doesNotContain(SENSITIVE_EMPLOYEE_REF);

        // C. No high precision GPS coordinates (>= 4 decimal places) in logs
        // Exclude version strings like 1.0.0 or typical dates
        Pattern coordPattern = Pattern.compile("(?<![\\w.-])([0-9]{1,2}\\.[0-9]{4,})(?![\\w.-])");
        for (var event : logAppender.getEvents()) {
            String msg = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                msg += " [throwable: " + event.getThrowableProxy().getMessage() + "]";
            }
            Matcher m = coordPattern.matcher(msg);
            if (m.find()) {
                throw new AssertionError("Found leaked raw GPS coordinate [" + m.group(1) + "] in logger [" + event.getLoggerName() + "]: " + msg);
            }
        }

        // D. Response bodies must never leak raw passwords
        for (String body : responseBodies) {
            assertThat(body).doesNotContain(SENSITIVE_PASSWORD);
        }
    }

    private void assertStandardErrorShape(String jsonBody, int expectedStatus) throws Exception {
        JsonNode node = objectMapper.readTree(jsonBody);
        assertThat(node.has("status")).as("Error response must contain status").isTrue();
        assertThat(node.get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(node.has("code")).as("Error response must contain code").isTrue();
        assertThat(node.has("message")).as("Error response must contain message").isTrue();
        assertThat(node.has("path")).as("Error response must contain path").isTrue();
        assertThat(node.has("timestamp")).as("Error response must contain timestamp").isTrue();
        assertThat(node.has("stackTrace")).as("Error response must NOT contain stackTrace").isFalse();
    }
}
