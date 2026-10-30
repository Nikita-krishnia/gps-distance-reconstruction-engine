package com.example.gpsengine.security;

import com.example.gpsengine.api.AuthController;
import com.example.gpsengine.api.HelloController;
import com.example.gpsengine.api.TripController;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.config.WebMvcConfig;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.error.ResourceNotFoundException;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@WebMvcTest({TripController.class, AuthController.class, HelloController.class})
@Import({
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        CorrelationIdFilter.class,
        WebMvcConfig.class,
        CurrentUserArgumentResolver.class
})
class SecurityRoleMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private CalculationService calculationService;

    @MockitoBean
    private AuthService authService;

    private static final String TENANT = "tenant-alpha";

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
    void setUpMocks() {
        TripDto.TripResponse tripResp = new TripDto.TripResponse(1L, TripStatus.NEW, BillingStatus.OPEN, Instant.now());
        when(tripService.createTrip(any(), any())).thenReturn(tripResp);
        when(tripService.uploadPings(any(), any(), any())).thenReturn(new PingDto.UploadPingsResponse(1));
        when(tripService.uploadMilestones(any(), any(), any())).thenReturn(new MilestoneDto.UploadMilestonesResponse(1));

        CalculationDto.CalculationResponse calcResp = new CalculationDto.CalculationResponse(
                1L, TripStatus.CALCULATED, "v1", 1000L, BigDecimal.valueOf(1.0),
                1, 0, 1, Instant.now(), false, null
        );
        when(calculationService.calculateTripDistance(any(), any())).thenReturn(calcResp);
        when(calculationService.closeInvoice(any(), any())).thenReturn(new TripDto.CloseInvoiceResponse(1L, 1000L, BigDecimal.valueOf(1.0), Instant.now()));

        TripDto.TripDetailResponse detailResp = new TripDto.TripDetailResponse(
                1L, TripStatus.CALCULATED, BillingStatus.OPEN, Instant.now(), 5, 2, null,
                null, null, null, null, null
        );
        when(tripService.getTripDetails(eq(1L), any())).thenReturn(detailResp);
        when(tripService.getEmployeeTripSummary(eq(1L), any(), eq("emp-001")))
                .thenReturn(new TripDto.EmployeeTripSummaryResponse(1L, TripStatus.CALCULATED, Instant.now(), 1.0));
        when(tripService.getEmployeeTripSummary(eq(1L), any(), eq("emp-other")))
                .thenThrow(new ResourceNotFoundException("Trip not found"));

        when(tripService.getCalculationHistory(any(), any())).thenReturn(List.of(calcResp));
        when(tripService.getCalculationSegments(any(), any(), any())).thenReturn(List.of());
        when(tripService.getBillingAdjustments(any(), any())).thenReturn(List.of());
    }

    @ParameterizedTest(name = "{0} {1} with role {2} -> expected status {3}")
    @CsvSource({
            // Public endpoints
            "GET,  /api/v1/hello,                         ANONYMOUS, 200",
            "GET,  /api/v1/hello,                         EMPLOYEE,  200",
            "GET,  /api/v1/hello,                         OPERATOR,  200",
            "GET,  /api/v1/hello,                         ADMIN,     200",

            // POST /trips
            "POST, /api/v1/trips,                         ANONYMOUS, 401",
            "POST, /api/v1/trips,                         EMPLOYEE,  403",
            "POST, /api/v1/trips,                         OPERATOR,  201",
            "POST, /api/v1/trips,                         ADMIN,     201",

            // POST /trips/{id}/pings
            "POST, /api/v1/trips/1/pings,                 ANONYMOUS, 401",
            "POST, /api/v1/trips/1/pings,                 EMPLOYEE,  403",
            "POST, /api/v1/trips/1/pings,                 OPERATOR,  201",
            "POST, /api/v1/trips/1/pings,                 ADMIN,     201",

            // POST /trips/{id}/milestones
            "POST, /api/v1/trips/1/milestones,            ANONYMOUS, 401",
            "POST, /api/v1/trips/1/milestones,            EMPLOYEE,  403",
            "POST, /api/v1/trips/1/milestones,            OPERATOR,  201",
            "POST, /api/v1/trips/1/milestones,            ADMIN,     201",

            // POST /trips/{id}/calculations
            "POST, /api/v1/trips/1/calculations,          ANONYMOUS, 401",
            "POST, /api/v1/trips/1/calculations,          EMPLOYEE,  403",
            "POST, /api/v1/trips/1/calculations,          OPERATOR,  201",
            "POST, /api/v1/trips/1/calculations,          ADMIN,     201",

            // GET /trips/{id}
            "GET,  /api/v1/trips/1,                        ANONYMOUS, 401",
            "GET,  /api/v1/trips/1,                        EMPLOYEE,  200",
            "GET,  /api/v1/trips/1,                        OPERATOR,  200",
            "GET,  /api/v1/trips/1,                        ADMIN,     200",

            // GET /trips/{id}/calculations
            "GET,  /api/v1/trips/1/calculations,          ANONYMOUS, 401",
            "GET,  /api/v1/trips/1/calculations,          EMPLOYEE,  403",
            "GET,  /api/v1/trips/1/calculations,          OPERATOR,  200",
            "GET,  /api/v1/trips/1/calculations,          ADMIN,     200",

            // GET /trips/{id}/calculations/{c}/segments
            "GET,  /api/v1/trips/1/calculations/1/segments, ANONYMOUS, 401",
            "GET,  /api/v1/trips/1/calculations/1/segments, EMPLOYEE,  403",
            "GET,  /api/v1/trips/1/calculations/1/segments, OPERATOR,  200",
            "GET,  /api/v1/trips/1/calculations/1/segments, ADMIN,     200",

            // GET /trips/{id}/billing-adjustments
            "GET,  /api/v1/trips/1/billing-adjustments,   ANONYMOUS, 401",
            "GET,  /api/v1/trips/1/billing-adjustments,   EMPLOYEE,  403",
            "GET,  /api/v1/trips/1/billing-adjustments,   OPERATOR,  200",
            "GET,  /api/v1/trips/1/billing-adjustments,   ADMIN,     200",

            // POST /trips/{id}/close-invoice
            "POST, /api/v1/trips/1/close-invoice,         ANONYMOUS, 401",
            "POST, /api/v1/trips/1/close-invoice,         EMPLOYEE,  403",
            "POST, /api/v1/trips/1/close-invoice,         OPERATOR,  403",
            "POST, /api/v1/trips/1/close-invoice,         ADMIN,     200"
    })
    @DisplayName("Role matrix validation across all endpoints and roles")
    void roleMatrixValidation(String method, String path, String role, int expectedStatus) throws Exception {
        var reqBuilder = "POST".equalsIgnoreCase(method) ? post(path) : get(path);

        if (!"ANONYMOUS".equals(role)) {
            String empRef = "EMPLOYEE".equals(role) ? "emp-001" : null;
            reqBuilder.with(asRole(role, TENANT, empRef));
        }

        if ("POST".equalsIgnoreCase(method)) {
            reqBuilder.contentType(MediaType.APPLICATION_JSON);
            if (path.equals("/api/v1/trips")) {
                reqBuilder.content("{\"officePlannedLatitude\": 12.9, \"officePlannedLongitude\": 77.6}");
            } else if (path.endsWith("/pings")) {
                reqBuilder.content("{\"pings\": [{\"latitude\": 12.9, \"longitude\": 77.6, \"recordedAt\": \"2026-10-30T10:00:00Z\"}]}");
            } else if (path.endsWith("/milestones")) {
                reqBuilder.content("{\"milestones\": [{\"type\": \"TRIP_START\", \"actualLatitude\": 12.9, \"actualLongitude\": 77.6, \"actualTime\": \"2026-10-30T10:00:00Z\"}]}");
            }
        }

        mockMvc.perform(reqBuilder)
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("401 response has standard error shape, correlationId, WWW-Authenticate: Bearer, and generic message")
    void unauthenticatedEnvelopeVerification() throws Exception {
        mockMvc.perform(get("/api/v1/trips/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, is("Bearer")))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.message", is("Authentication required")))
                .andExpect(jsonPath("$.path", is("/api/v1/trips/1")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.message", not(containsString("expired"))))
                .andExpect(jsonPath("$.message", not(containsString("signature"))));
    }

    @Test
    @DisplayName("403 response has standard error shape, correlationId, and generic message")
    void forbiddenEnvelopeVerification() throws Exception {
        mockMvc.perform(post("/api/v1/trips/1/close-invoice")
                        .with(asRole("OPERATOR", TENANT, null)))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")))
                .andExpect(jsonPath("$.message", is("Access is denied")))
                .andExpect(jsonPath("$.path", is("/api/v1/trips/1/close-invoice")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("Client header X-Tenant-Id is ignored: tenant comes strictly from token claim")
    void tenantComesStrictlyFromToken() throws Exception {
        mockMvc.perform(get("/api/v1/trips/1")
                        .with(asRole("OPERATOR", "tenant-from-token", null))
                        .header("X-Tenant-Id", "tenant-spoofed-by-client"))
                .andExpect(status().isOk());

        // Verify service lookup received tenant-from-token, NOT spoofed client header
        verify(tripService).getTripDetails(1L, "tenant-from-token");
    }

    @Test
    @DisplayName("Request with only X-Tenant-Id header and no token returns 401 UNAUTHENTICATED")
    void requestWithOnlyTenantHeaderReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/trips/1")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @Test
    @DisplayName("Employee accessing unparticipated trip receives 404 RESOURCE_NOT_FOUND, not 403")
    void employeeNotParticipantReceives404() throws Exception {
        mockMvc.perform(get("/api/v1/trips/1")
                        .with(asRole("EMPLOYEE", TENANT, "emp-other")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    @DisplayName("Login validation errors return 400 with VALIDATION_ERROR and fieldErrors list")
    void loginValidationErrors() throws Exception {
        String invalidLogin = "{\"username\": \"\", \"password\": \"\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidLogin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }
}

