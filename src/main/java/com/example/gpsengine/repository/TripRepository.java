package com.example.gpsengine.repository;

import com.example.gpsengine.domain.Trip;
import com.example.gpsengine.domain.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findByIdAndTenantId(Long id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id AND t.tenantId = :tenantId")
    Optional<Trip> findByIdAndTenantIdForUpdate(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Query("SELECT t FROM Trip t WHERE t.status = :status ORDER BY t.createdAt ASC, t.id ASC")
    Page<Trip> findByStatusOrderByCreatedAtAscIdAsc(@Param("status") TripStatus status, Pageable pageable);

    long countByStatus(TripStatus status);

    @Query("SELECT MIN(t.createdAt) FROM Trip t WHERE t.status = :status")
    Optional<java.time.Instant> findOldestCreatedAtByStatus(@Param("status") TripStatus status);
}
