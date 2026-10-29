package com.example.gpsengine.repository;

import com.example.gpsengine.domain.BillingAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingAdjustmentRepository extends JpaRepository<BillingAdjustment, Long> {

    List<BillingAdjustment> findByTripIdOrderByCreatedAtDesc(Long tripId);

    List<BillingAdjustment> findByTripIdAndTenantIdOrderByCreatedAtDesc(Long tripId, String tenantId);
}

