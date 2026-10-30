package com.example.gpsengine.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.security.CorrelationIdFilter;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

@org.junit.jupiter.api.Tag("integration")
@SpringBootTest(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=true",
        "recompute.interval-seconds=3600",
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.endpoint.health.show-details=never",
        "management.endpoint.health.probes.enabled=true",
        "jwt.secret=ThisIsATestSecretKeyWithAtLeast32BytesForHmacSha256TestingOnly!"
})
@AutoConfigureMockMvc
@Testcontainers
class ActuatorSecurityAndPrivacyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TripService tripService;

    @MockBean
    private CalculationService calculationService;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger rootLogger;

    private static final String SYNTHETIC_EMP = "emp-secret-999";
    private static final double SYNTHETIC_LAT = 12.998877;
    private static final double SYNTHETIC_LON = 77.665544;

    private RequestPostProcessor asRole(String role, String tenant, String employeeRef) {
        return jwt().jwt(builder -> {
            builder.subject("100")
                    .claim("tenant", tenant)
                    .claim("role", role);
            if (employeeRef != null) {
                builder.claim("employeeRef", employeeRef);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @BeforeEach
    void setUpLoggingCapture() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        when(tripService.createTrip(any(), any()))
                .thenReturn(new TripDto.TripResponse(1L, TripStatus.NEW, BillingStatus.OPEN, Instant.now()));
        when(tripService.uploadPings(any(), any(), any()))
                .thenReturn(new PingDto.UploadPingsResponse(1));
        when(tripService.uploadMilestones(any(), any(), any()))
                .thenReturn(new MilestoneDto.UploadMilestonesResponse(1));

        CalculationDto.CalculationResponse calcResp = new CalculationDto.CalculationResponse(
                1L, TripStatus.CALCULATED, "v1", 5000L, BigDecimal.valueOf(5.0),
                2, 1, 1, Instant.now(), false, null
        );
        when(calculationService.calculateTripDistance(any(), any())).thenReturn(calcResp);
    }

    @AfterEach
    void tearDownLoggingCapture() {
        if (rootLogger != null && listAppender != null) {
            rootLogger.detachAppender(listAppender);
        }
        MDC.clear();
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    @DisplayName("GET /actuator/health is 200 without token and returns status UP with no component details")
    void healthEndpointIsPublicAndShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("/actuator/metrics returns 401 without token, 403 for EMPLOYEE/OPERATOR, 200 for ADMIN")
    void metricsEndpointRoleMatrix() throws Exception {
        // 1. Unauthenticated -> 401
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));

        // 2. EMPLOYEE -> 403
        mockMvc.perform(get("/actuator/metrics")
                        .with(asRole("EMPLOYEE", "tenant-test", "emp-001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        // 3. OPERATOR -> 403
        mockMvc.perform(get("/actuator/metrics")
                        .with(asRole("OPERATOR", "tenant-test", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        // 4. ADMIN -> 200
        mockMvc.perform(get("/actuator/metrics")
                        .with(asRole("ADMIN", "tenant-test", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }

    @Test
    @DisplayName("Unknown actuator paths (e.g. /actuator/beans) are denied")
    void unknownActuatorPathsAreDenied() throws Exception {
        // Unauthenticated -> 401
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized());

        // Authenticated as OPERATOR -> 403
        mockMvc.perform(get("/actuator/beans")
                        .with(asRole("OPERATOR", "tenant-test", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Meter tags validation: zero coordinates, employee refs, or tenant IDs in meter tags")
    void meterTagsContainNoPersonalDataOrHighCardinality() throws Exception {
        // Perform a complete trip flow as OPERATOR
        mockMvc.perform(post("/api/v1/trips")
                .with(asRole("OPERATOR", "tenant-privacy", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officePlannedLatitude\": " + SYNTHETIC_LAT + ", \"officePlannedLongitude\": " + SYNTHETIC_LON + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/1/pings")
                .with(asRole("OPERATOR", "tenant-privacy", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pings\": [{\"latitude\": " + SYNTHETIC_LAT + ", \"longitude\": " + SYNTHETIC_LON + ", \"recordedAt\": \"2026-10-30T10:00:00Z\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/1/milestones")
                .with(asRole("OPERATOR", "tenant-privacy", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestones\": [{\"type\": \"PICKUP\", \"employeeRef\": \"" + SYNTHETIC_EMP + "\", \"actualLatitude\": " + SYNTHETIC_LAT + ", \"actualLongitude\": " + SYNTHETIC_LON + ", \"actualTime\": \"2026-10-30T10:05:00Z\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/1/calculations")
                .with(asRole("OPERATOR", "tenant-privacy", null)))
                .andExpect(status().isCreated());

        // Scan ALL meters and tags registered in Micrometer
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            for (Tag tag : id.getTags()) {
                String key = tag.getKey();
                String value = tag.getValue();

                assertThat(value).doesNotContain(SYNTHETIC_EMP);
                assertThat(value).doesNotContain(String.valueOf(SYNTHETIC_LAT));
                assertThat(value).doesNotContain(String.valueOf(SYNTHETIC_LON));
                assertThat(value).doesNotContain("tenant-privacy");
                assertThat(key).doesNotContain("employee");
                assertThat(key).doesNotContain("coordinate");
                assertThat(key).doesNotContain("tenant");
            }
        }
    }

    @Test
    @DisplayName("MDC isolation: MDC does not leak between sequential requests on the same thread")
    void mdcDoesNotLeakAcrossSequentialRequests() throws Exception {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        // Request 1: with Correlation ID header
        mockMvc.perform(get("/api/v1/hello")
                        .header("X-Correlation-Id", "corr-req-1"))
                .andExpect(status().isOk());

        // Immediately verify MDC was cleared on the thread after request completion
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("userId")).isNull();

        // Request 2: with different Correlation ID header
        mockMvc.perform(get("/api/v1/hello")
                        .header("X-Correlation-Id", "corr-req-2"))
                .andExpect(status().isOk());

        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("Logs captured during full flow contain correlationId and zero coordinates or passwords")
    void logsContainNoPersonalDataOrSecrets() throws Exception {
        String customCorrId = "audit-corr-test-12345";

        mockMvc.perform(post("/api/v1/trips")
                .with(asRole("OPERATOR", "tenant-audit", null))
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, customCorrId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officePlannedLatitude\": " + SYNTHETIC_LAT + ", \"officePlannedLongitude\": " + SYNTHETIC_LON + "}"))
                .andExpect(status().isCreated());

        boolean hasLogWithCorrelation = false;
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();

            // Verify no coordinates or employee references leaked
            assertThat(msg).doesNotContain(String.valueOf(SYNTHETIC_LAT));
            assertThat(msg).doesNotContain(String.valueOf(SYNTHETIC_LON));
            assertThat(msg).doesNotContain("Password");
            assertThat(msg).doesNotContain("secret");

            if (event.getMDCPropertyMap() != null && customCorrId.equals(event.getMDCPropertyMap().get("correlationId"))) {
                hasLogWithCorrelation = true;
            }
        }

        assertThat(hasLogWithCorrelation).isTrue();
    }
}
