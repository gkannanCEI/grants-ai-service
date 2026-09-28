package com.example.agent.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ReservationSummary}.
 */
@Repository
public interface ReservationSummaryRepository extends JpaRepository<ReservationSummary, String> {

    /** All summaries for a reservation, newest first. */
    List<ReservationSummary> findByReservationIdOrderByCreatedAtDesc(String reservationId);
}
