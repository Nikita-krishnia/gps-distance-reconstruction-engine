package com.example.gpsengine.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error JSON shape for all API error responses.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldErrorDetail> fieldErrors
) {
    public ErrorResponse(Instant timestamp, int status, String code, String message, String path, String correlationId) {
        this(timestamp, status, code, message, path, correlationId, List.of());
    }

    public record FieldErrorDetail(
            String field,
            String message
    ) {}
}

