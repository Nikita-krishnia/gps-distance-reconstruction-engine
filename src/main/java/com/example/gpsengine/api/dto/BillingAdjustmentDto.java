package com.example.gpsengine.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class BillingAdjustmentDto {

    public record AdjustmentResponse(
            Long id,
            Long oldCalculationId,
            Long newCalculationId,
            long deltaMeters,
            BigDecimal deltaKm,
            Instant createdAt
    ) {
        public static AdjustmentResponse from(
                Long id,
                Long oldCalculationId,
                Long newCalculationId,
                long deltaMeters,
                Instant createdAt
        ) {
            BigDecimal deltaKm = BigDecimal.valueOf(deltaMeters)
                    .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
            return new AdjustmentResponse(
                    id,
                    oldCalculationId,
                    newCalculationId,
                    deltaMeters,
                    deltaKm,
                    createdAt
            );
        }
    }
}

