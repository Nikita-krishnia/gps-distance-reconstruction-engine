package com.example.gpsengine.security;

import com.example.gpsengine.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Authentication entry point returning standard error envelopes with UNAUTHENTICATED code on 401.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final com.example.gpsengine.monitoring.GpsMetrics metrics;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this(objectMapper, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RestAuthenticationEntryPoint(ObjectMapper objectMapper,
                                        java.util.Optional<com.example.gpsengine.monitoring.GpsMetrics> metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics.orElse(null);
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Authentication failed: {}", authException.getClass().getSimpleName());
        if (metrics != null) {
            metrics.recordAuthDenied("401");
        }

        String correlationId = CorrelationIdFilter.getCorrelationId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHENTICATED",
                "Authentication required",
                request.getRequestURI(),
                correlationId
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}

