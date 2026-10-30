package com.example.gpsengine.api.dto;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class MilestoneDto {

    public record UploadMilestonesRequest(
            @NotNull(message = "milestones list must not be null")
            @NotEmpty(message = "milestones list must not be empty")
            @Size(min = 1, max = 500, message = "milestones list size must be between 1 and 500")
            List<@Valid MilestoneItem> milestones
    ) {}

    public record MilestoneItem(
            @NotNull(message = "type must not be null")
            MilestoneType type,

            @Size(max = 64, message = "employeeRef length must not exceed 64 characters")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "employeeRef must contain only alphanumeric characters, dashes or underscores")
            String employeeRef,

            RiderKind riderKind,

            @DecimalMin(value = "-90.0", message = "actualLatitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "actualLatitude must be between -90 and 90")
            Double actualLatitude,

            @DecimalMin(value = "-180.0", message = "actualLongitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "actualLongitude must be between -180 and 180")
            Double actualLongitude,

            Instant actualTime,

            @DecimalMin(value = "-90.0", message = "plannedLatitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "plannedLatitude must be between -90 and 90")
            Double plannedLatitude,

            @DecimalMin(value = "-180.0", message = "plannedLongitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "plannedLongitude must be between -180 and 180")
            Double plannedLongitude,

            Instant plannedTime,

            @DecimalMin(value = "-90.0", message = "profileLatitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "profileLatitude must be between -90 and 90")
            Double profileLatitude,

            @DecimalMin(value = "-180.0", message = "profileLongitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "profileLongitude must be between -180 and 180")
            Double profileLongitude,

            Instant profileSignInTime
    ) {
        public MilestoneItem {
            // Coordinate pairing cross-field validation
            validateCoordPair("actualLatitude", actualLatitude, "actualLongitude", actualLongitude);
            validateCoordPair("plannedLatitude", plannedLatitude, "plannedLongitude", plannedLongitude);
            validateCoordPair("profileLatitude", profileLatitude, "profileLongitude", profileLongitude);

            // EmployeeRef presence requirement for PICKUP, DROP, NO_SHOW
            if (type != null && (type == MilestoneType.PICKUP || type == MilestoneType.DROP || type == MilestoneType.NO_SHOW)) {
                if (employeeRef == null || employeeRef.isBlank()) {
                    throw new IllegalArgumentException("employeeRef is required for milestone type " + type);
                }
            }
        }

        private static void validateCoordPair(String latName, Double lat, String lonName, Double lon) {
            if ((lat == null && lon != null) || (lat != null && lon == null)) {
                throw new IllegalArgumentException(latName + " and " + lonName + " must both be present or both absent");
            }
            if (lat != null && (lat.isNaN() || lat.isInfinite())) {
                throw new IllegalArgumentException(latName + " must be a finite number");
            }
            if (lon != null && (lon.isNaN() || lon.isInfinite())) {
                throw new IllegalArgumentException(lonName + " must be a finite number");
            }
        }
    }

    public record UploadMilestonesResponse(
            int accepted
    ) {}
}

