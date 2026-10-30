package com.example.gpsengine.security;

import com.example.gpsengine.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Access denied handler returning standard error envelopes with ACCESS_DENIED code on 403.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final com.example.gpsengine.monitoring.GpsMetrics metrics;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this(objectMapper, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RestAccessDeniedHandler(ObjectMapper objectMapper,
                                   java.util.Optional<com.example.gpsengine.monitoring.GpsMetrics> metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics.orElse(null);
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.debug("Access denied: {}", accessDeniedException.getClass().getSimpleName());
        if (metrics != null) {
            metrics.recordAuthDenied("403");
        }

        String correlationId = CorrelationIdFilter.getCorrelationId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "Access is denied",
                request.getRequestURI(),
                correlationId
        );

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}

