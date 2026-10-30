package com.example.gpsengine.repository;

import com.example.gpsengine.domain.BillingAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillingAdjustmentRepository extends JpaRepository<BillingAdjustment, Long> {

    List<BillingAdjustment> findByTripIdOrderByCreatedAtDesc(Long tripId);

    List<BillingAdjustment> findByTripIdAndTenantIdOrderByCreatedAtDesc(Long tripId, String tenantId);

    @Query("SELECT COALESCE(SUM(b.deltaMeters), 0L) FROM BillingAdjustment b WHERE b.trip.id = :tripId AND b.tenantId = :tenantId")
    long sumDeltaMetersByTripIdAndTenantId(@Param("tripId") Long tripId, @Param("tenantId") String tenantId);

    @Query("SELECT b FROM BillingAdjustment b WHERE b.trip.id = :tripId AND b.tenantId = :tenantId ORDER BY b.createdAt DESC, b.id DESC LIMIT 1")
    Optional<BillingAdjustment> findLatestByTripIdAndTenantId(@Param("tripId") Long tripId, @Param("tenantId") String tenantId);
}
