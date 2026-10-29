package com.example.gpsengine.repository;

import com.example.gpsengine.domain.GpsPing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GpsPingRepository extends JpaRepository<GpsPing, Long> {

    List<GpsPing> findByTripIdOrderByRecordedAtAsc(Long tripId);

    List<GpsPing> findByTripIdAndTenantIdOrderByRecordedAtAsc(Long tripId, String tenantId);
}

