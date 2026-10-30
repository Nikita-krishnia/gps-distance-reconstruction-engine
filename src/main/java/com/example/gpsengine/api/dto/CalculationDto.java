package com.example.gpsengine.api.dto;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class CalculationDto {

    public record CalculationResponse(
            Long id,
            TripStatus status,
            String ruleVersion,
            long totalMeters,
            BigDecimal totalKm,
            int routingProviderCalls,
            int cacheHits,
            int cacheMisses,
            Instant createdAt,
            boolean reused,
            BillingAdjustmentDto.AdjustmentResponse adjustment
    ) {
        public static CalculationResponse from(
                Long id,
                TripStatus status,
                String ruleVersion,
                long totalMeters,
                int routingProviderCalls,
                int cacheHits,
                int cacheMisses,
                Instant createdAt,
                boolean reused,
                BillingAdjustmentDto.AdjustmentResponse adjustment
        ) {
            BigDecimal totalKm = BigDecimal.valueOf(totalMeters)
                    .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
            return new CalculationResponse(
                    id,
                    status,
                    ruleVersion,
                    totalMeters,
                    totalKm,
                    routingProviderCalls,
                    cacheHits,
                    cacheMisses,
                    createdAt,
                    reused,
                    adjustment
            );
        }

        public static CalculationResponse from(
                Long id,
                TripStatus status,
                String ruleVersion,
                long totalMeters,
                int routingProviderCalls,
                int cacheHits,
                int cacheMisses,
                Instant createdAt
        ) {
            return from(id, status, ruleVersion, totalMeters, routingProviderCalls, cacheHits, cacheMisses, createdAt, false, null);
        }
    }

    public record CalculationSummaryResponse(
            Long id,
            TripStatus status,
            String ruleVersion,
            long totalMeters,
            BigDecimal totalKm,
            Instant createdAt
    ) {
        public static CalculationSummaryResponse from(Long id, TripStatus status, String ruleVersion, long totalMeters, Instant createdAt) {
            BigDecimal totalKm = BigDecimal.valueOf(totalMeters)
                    .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
            return new CalculationSummaryResponse(id, status, ruleVersion, totalMeters, totalKm, createdAt);
        }
    }

    public record SegmentResponse(
            int sequenceNumber,
            double fromLatitude,
            double fromLongitude,
            Instant fromTime,
            double toLatitude,
            double toLongitude,
            Instant toTime,
            long meters,
            SegmentRule rule,
            String reason
    ) {}
}
