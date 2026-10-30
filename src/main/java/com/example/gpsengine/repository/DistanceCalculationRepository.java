package com.example.gpsengine.repository;

import com.example.gpsengine.domain.DistanceCalculation;
import com.example.gpsengine.domain.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DistanceCalculationRepository extends JpaRepository<DistanceCalculation, Long> {

    List<DistanceCalculation> findByTripIdOrderByCreatedAtDesc(Long tripId);

    List<DistanceCalculation> findByTripIdAndTenantIdOrderByCreatedAtDesc(Long tripId, String tenantId);

    @Query("SELECT c FROM DistanceCalculation c WHERE c.trip.id = :tripId AND c.inputHash = :inputHash AND c.ruleVersion = :ruleVersion AND c.status = :status AND c.tenantId = :tenantId")
    Optional<DistanceCalculation> findByTripIdAndInputHashAndRuleVersionAndStatusAndTenantId(
            @Param("tripId") Long tripId,
            @Param("inputHash") String inputHash,
            @Param("ruleVersion") String ruleVersion,
            @Param("status") TripStatus status,
            @Param("tenantId") String tenantId);
}
