package com.example.gpsengine.repository;

import com.example.gpsengine.domain.TripMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripMilestoneRepository extends JpaRepository<TripMilestone, Long> {

    List<TripMilestone> findByTripId(Long tripId);

    List<TripMilestone> findByTripIdAndTenantId(Long tripId, String tenantId);
}

