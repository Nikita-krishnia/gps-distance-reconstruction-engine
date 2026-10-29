package com.example.gpsengine.repository;

import com.example.gpsengine.domain.DistanceCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DistanceCalculationRepository extends JpaRepository<DistanceCalculation, Long> {

    List<DistanceCalculation> findByTripIdOrderByCreatedAtDesc(Long tripId);

    List<DistanceCalculation> findByTripIdAndTenantIdOrderByCreatedAtDesc(Long tripId, String tenantId);
}

