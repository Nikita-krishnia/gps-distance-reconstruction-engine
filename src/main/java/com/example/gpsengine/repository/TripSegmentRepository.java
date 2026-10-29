package com.example.gpsengine.repository;

import com.example.gpsengine.domain.TripSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripSegmentRepository extends JpaRepository<TripSegment, Long> {

    List<TripSegment> findByCalculationIdOrderBySequenceNumberAsc(Long calculationId);
}

