package com.example.gpsengine.api;

import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.config.WebMvcConfig;
import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.security.CorrelationIdFilter;
import com.example.gpsengine.security.CurrentUserArgumentResolver;
import com.example.gpsengine.security.RestAccessDeniedHandler;
import com.example.gpsengine.security.RestAuthenticationEntryPoint;
import com.example.gpsengine.security.SecurityConfiguration;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

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
@WebMvcTest(TripController.class)
@Import({
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        CorrelationIdFilter.class,
        WebMvcConfig.class,
        CurrentUserArgumentResolver.class
})
class TripControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private CalculationService calculationService;

    private static final String TENANT = "tenant-alpha";

    private RequestPostProcessor asOperator() {
        return jwt().jwt(builder -> builder
                .subject("100")
                .claim("tenant", TENANT)
                .claim("role", "OPERATOR")
        ).authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    @Test
    @DisplayName("POST /trips creates trip and sets Location header")
    void createTripSuccess() throws Exception {
        TripDto.TripResponse mockResponse = new TripDto.TripResponse(
                42L, TripStatus.NEW, BillingStatus.OPEN, Instant.parse("2026-10-30T10:00:00Z")
        );
        when(tripService.createTrip(any(), eq(TENANT))).thenReturn(mockResponse);

        String body = """
                {
                  "officePlannedLatitude": 12.9279,
                  "officePlannedLongitude": 77.6271
                }
                """;

        mockMvc.perform(post("/api/v1/trips")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/trips/42")))
                .andExpect(jsonPath("$.id", is(42)))
                .andExpect(jsonPath("$.status", is("NEW")))
                .andExpect(jsonPath("$.billingStatus", is("OPEN")));
    }

    @Test
    @DisplayName("Missing authentication token returns 401 UNAUTHENTICATED with standard error envelope")
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officePlannedLatitude\": 12.0, \"officePlannedLongitude\": 77.0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("Client header X-Tenant-Id is ignored and token's tenant claim is used")
    void clientTenantHeaderIgnoredAndUsesTokenTenant() throws Exception {
        TripDto.TripResponse mockResponse = new TripDto.TripResponse(
                42L, TripStatus.NEW, BillingStatus.OPEN, Instant.now()
        );
        when(tripService.createTrip(any(), eq(TENANT))).thenReturn(mockResponse);

        String body = """
                {
                  "officePlannedLatitude": 12.9279,
                  "officePlannedLongitude": 77.6271
                }
                """;

        mockMvc.perform(post("/api/v1/trips")
                        .with(asOperator())
                        .header("X-Tenant-Id", "malicious-spoofed-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(tripService).createTrip(any(), eq(TENANT));
    }

    @Test
    @DisplayName("Invalid coordinate ranges return 400 with field errors")
    void invalidCoordinateValidation() throws Exception {
        String body = """
                {
                  "officePlannedLatitude": 95.0,
                  "officePlannedLongitude": -185.0
                }
                """;

        mockMvc.perform(post("/api/v1/trips")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("Proof of no data leaks: rejected coordinate and employeeRef values are never echoed back")
    void proofOfNoDataLeaksInErrorResponses() throws Exception {
        String sensitiveCoord = "123.456789";

        String body = String.format("""
                {
                  "milestones": [
                    {
                      "type": "PICKUP",
                      "employeeRef": "invalid@format#emp",
                      "actualLatitude": %s,
                      "actualLongitude": 77.0
                    }
                  ]
                }
                """, sensitiveCoord);

        mockMvc.perform(post("/api/v1/trips/1/milestones")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").value(not(containsString(sensitiveCoord))))
                .andExpect(jsonPath("$.message").value(not(containsString("invalid@format#emp"))));
    }

    @Test
    @DisplayName("Generic 500 error hides exception details and logs correlationId")
    void generic500HidesSensitiveExceptionMessage() throws Exception {
        when(calculationService.calculateTripDistance(eq(1L), eq(TENANT)))
                .thenThrow(new RuntimeException("secret 12.345678,77.1 failure in database connection"));

        mockMvc.perform(post("/api/v1/trips/1/calculations")
                        .with(asOperator()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
                .andExpect(jsonPath("$.message", is("Unexpected error occurred")))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.message").value(not(containsString("12.345678"))));
    }

    @Test
    @DisplayName("Non-numeric tripId parameter returns 400 TYPE_MISMATCH")
    void nonNumericTripIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/trips/abc")
                        .with(asOperator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("TYPE_MISMATCH")));
    }

    @Test
    @DisplayName("Unsupported HTTP method returns 405 METHOD_NOT_ALLOWED")
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(post("/api/v1/trips/1/billing-adjustments")
                        .with(asOperator()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", is("METHOD_NOT_ALLOWED")));
    }
}
