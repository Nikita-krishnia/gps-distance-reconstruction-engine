package com.example.gpsengine.repository;

import com.example.gpsengine.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findByIdAndTenantId(Long id, String tenantId);
}

