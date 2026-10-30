package com.example.gpsengine.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class PingDto {

    public record UploadPingsRequest(
            @NotNull(message = "pings list must not be null")
            @NotEmpty(message = "pings list must not be empty")
            @Size(min = 1, max = 10000, message = "pings list size must be between 1 and 10000")
            List<@Valid PingItem> pings
    ) {
        @Override
        public String toString() {
            return "UploadPingsRequest[count=" + (pings != null ? pings.size() : 0) + "]";
        }
    }

    public record PingItem(
            @NotNull(message = "latitude must not be null")
            @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90")
            Double latitude,

            @NotNull(message = "longitude must not be null")
            @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180")
            Double longitude,

            @NotNull(message = "recordedAt must not be null")
            Instant recordedAt,

            @DecimalMin(value = "0.0", message = "accuracyMeters must be non-negative")
            Double accuracyMeters
    ) {
        public PingItem {
            if (latitude != null && (latitude.isNaN() || latitude.isInfinite())) {
                throw new IllegalArgumentException("latitude must be a finite number");
            }
            if (longitude != null && (longitude.isNaN() || longitude.isInfinite())) {
                throw new IllegalArgumentException("longitude must be a finite number");
            }
            if (accuracyMeters != null && (accuracyMeters.isNaN() || accuracyMeters.isInfinite())) {
                throw new IllegalArgumentException("accuracyMeters must be a finite number");
            }
        }

        @Override
        public String toString() {
            return "PingItem[latitude=[REDACTED], longitude=[REDACTED], recordedAt=" + recordedAt + ", accuracyMeters=" + accuracyMeters + "]";
        }
    }

    public record UploadPingsResponse(
            int accepted
    ) {}
}

