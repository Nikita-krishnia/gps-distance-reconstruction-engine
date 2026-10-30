package com.example.gpsengine.api;

import com.example.gpsengine.api.dto.BillingAdjustmentDto;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * REST controller exposing endpoints for trip management, ping/milestone ingestion,
 * and distance reconstruction calculations.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final TripService tripService;
    private final CalculationService calculationService;

    public TripController(TripService tripService, CalculationService calculationService) {
        this.tripService = tripService;
        this.calculationService = calculationService;
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("X-Tenant-Id header must not be blank");
        }
        if (!TENANT_ID_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("X-Tenant-Id header is invalid: must be 1-64 alphanumeric characters, dashes, or underscores");
        }
    }

    @PostMapping
    public ResponseEntity<TripDto.TripResponse> createTrip(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody TripDto.CreateTripRequest request) {
        validateTenantId(tenantId);
        TripDto.TripResponse response = tripService.createTrip(request, tenantId.trim());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{tripId}/pings")
    public ResponseEntity<PingDto.UploadPingsResponse> uploadPings(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId,
            @Valid @RequestBody PingDto.UploadPingsRequest request) {
        validateTenantId(tenantId);
        PingDto.UploadPingsResponse response = tripService.uploadPings(tripId, tenantId.trim(), request);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{tripId}/milestones")
    public ResponseEntity<MilestoneDto.UploadMilestonesResponse> uploadMilestones(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId,
            @Valid @RequestBody MilestoneDto.UploadMilestonesRequest request) {
        validateTenantId(tenantId);
        MilestoneDto.UploadMilestonesResponse response = tripService.uploadMilestones(tripId, tenantId.trim(), request);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{tripId}/calculations")
    public ResponseEntity<CalculationDto.CalculationResponse> calculateTripDistance(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId) {
        validateTenantId(tenantId);
        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(tripId, tenantId.trim());
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<TripDto.TripDetailResponse> getTripDetails(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId) {
        validateTenantId(tenantId);
        TripDto.TripDetailResponse response = tripService.getTripDetails(tripId, tenantId.trim());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/calculations")
    public ResponseEntity<List<CalculationDto.CalculationResponse>> getCalculationHistory(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId) {
        validateTenantId(tenantId);
        List<CalculationDto.CalculationResponse> response = tripService.getCalculationHistory(tripId, tenantId.trim());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/calculations/{calculationId}/segments")
    public ResponseEntity<List<CalculationDto.SegmentResponse>> getCalculationSegments(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId,
            @PathVariable Long calculationId) {
        validateTenantId(tenantId);
        List<CalculationDto.SegmentResponse> response = tripService.getCalculationSegments(tripId, calculationId, tenantId.trim());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/billing-adjustments")
    public ResponseEntity<List<BillingAdjustmentDto.AdjustmentResponse>> getBillingAdjustments(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable Long tripId) {
        validateTenantId(tenantId);
        List<BillingAdjustmentDto.AdjustmentResponse> response = tripService.getBillingAdjustments(tripId, tenantId.trim());
        return ResponseEntity.ok(response);
    }
}

