package com.example.gpsengine.api;

import com.example.gpsengine.api.dto.BillingAdjustmentDto;
import com.example.gpsengine.api.dto.CalculationDto;
import com.example.gpsengine.api.dto.MilestoneDto;
import com.example.gpsengine.api.dto.PingDto;
import com.example.gpsengine.api.dto.TripDto;
import com.example.gpsengine.security.CurrentUser;
import com.example.gpsengine.service.CalculationService;
import com.example.gpsengine.service.TripService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST controller exposing endpoints for trip management, ping/milestone ingestion,
 * distance reconstruction calculations, and invoice closure.
 * Multi-tenant isolation is enforced via verified JWT claims encapsulated in {@link CurrentUser}.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;
    private final CalculationService calculationService;

    public TripController(TripService tripService, CalculationService calculationService) {
        this.tripService = tripService;
        this.calculationService = calculationService;
    }

    @PostMapping
    public ResponseEntity<TripDto.TripResponse> createTrip(
            CurrentUser user,
            @Valid @RequestBody TripDto.CreateTripRequest request) {
        TripDto.TripResponse response = tripService.createTrip(request, user.tenantId());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{tripId}/pings")
    public ResponseEntity<PingDto.UploadPingsResponse> uploadPings(
            CurrentUser user,
            @PathVariable Long tripId,
            @Valid @RequestBody PingDto.UploadPingsRequest request) {
        PingDto.UploadPingsResponse response = tripService.uploadPings(tripId, user.tenantId(), request);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{tripId}/milestones")
    public ResponseEntity<MilestoneDto.UploadMilestonesResponse> uploadMilestones(
            CurrentUser user,
            @PathVariable Long tripId,
            @Valid @RequestBody MilestoneDto.UploadMilestonesRequest request) {
        MilestoneDto.UploadMilestonesResponse response = tripService.uploadMilestones(tripId, user.tenantId(), request);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{tripId}/calculations")
    public ResponseEntity<CalculationDto.CalculationResponse> calculateTripDistance(
            CurrentUser user,
            @PathVariable Long tripId) {
        CalculationDto.CalculationResponse response = calculationService.calculateTripDistance(tripId, user.tenantId());
        if (response.reused()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{tripId}/close-invoice")
    public ResponseEntity<TripDto.CloseInvoiceResponse> closeInvoice(
            CurrentUser user,
            @PathVariable Long tripId) {
        TripDto.CloseInvoiceResponse response = calculationService.closeInvoice(tripId, user.tenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<?> getTripDetails(
            CurrentUser user,
            @PathVariable Long tripId) {
        if (user != null && user.isEmployee()) {
            TripDto.EmployeeTripSummaryResponse summary = tripService.getEmployeeTripSummary(
                    tripId, user.tenantId(), user.employeeRef()
            );
            return ResponseEntity.ok(summary);
        }
        TripDto.TripDetailResponse response = tripService.getTripDetails(tripId, user.tenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/calculations")
    public ResponseEntity<List<CalculationDto.CalculationResponse>> getCalculationHistory(
            CurrentUser user,
            @PathVariable Long tripId) {
        List<CalculationDto.CalculationResponse> response = tripService.getCalculationHistory(tripId, user.tenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/calculations/{calculationId}/segments")
    public ResponseEntity<List<CalculationDto.SegmentResponse>> getCalculationSegments(
            CurrentUser user,
            @PathVariable Long tripId,
            @PathVariable Long calculationId) {
        List<CalculationDto.SegmentResponse> response = tripService.getCalculationSegments(tripId, calculationId, user.tenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tripId}/billing-adjustments")
    public ResponseEntity<List<BillingAdjustmentDto.AdjustmentResponse>> getBillingAdjustments(
            CurrentUser user,
            @PathVariable Long tripId) {
        List<BillingAdjustmentDto.AdjustmentResponse> response = tripService.getBillingAdjustments(tripId, user.tenantId());
        return ResponseEntity.ok(response);
    }
}
