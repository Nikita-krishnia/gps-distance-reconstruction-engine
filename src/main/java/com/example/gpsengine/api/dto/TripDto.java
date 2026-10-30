package com.example.gpsengine.api.dto;

import com.example.gpsengine.domain.BillingStatus;
import com.example.gpsengine.domain.TripStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class TripDto {

    public record CreateTripRequest(
            @NotNull(message = "officePlannedLatitude must not be null")
            @DecimalMin(value = "-90.0", message = "officePlannedLatitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "officePlannedLatitude must be between -90 and 90")
            Double officePlannedLatitude,

            @NotNull(message = "officePlannedLongitude must not be null")
            @DecimalMin(value = "-180.0", message = "officePlannedLongitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "officePlannedLongitude must be between -180 and 180")
            Double officePlannedLongitude
    ) {
        public CreateTripRequest {
            if (officePlannedLatitude != null && (officePlannedLatitude.isNaN() || officePlannedLatitude.isInfinite())) {
                throw new IllegalArgumentException("officePlannedLatitude must be a finite number");
            }
            if (officePlannedLongitude != null && (officePlannedLongitude.isNaN() || officePlannedLongitude.isInfinite())) {
                throw new IllegalArgumentException("officePlannedLongitude must be a finite number");
            }
        }
    }

    public record TripResponse(
            Long id,
            TripStatus status,
            BillingStatus billingStatus,
            Instant createdAt
    ) {}

    public record TripDetailResponse(
            Long id,
            TripStatus status,
            BillingStatus billingStatus,
            Instant createdAt,
            long pingCount,
            long milestoneCount,
            CalculationDto.CalculationSummaryResponse latestCalculation
    ) {}
}

