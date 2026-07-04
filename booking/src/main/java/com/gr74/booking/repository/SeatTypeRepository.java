package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.booking.model.SeatType;

/**
 * Spring Data repository for {@link SeatType}. {@code existsByName} pre-checks the
 * {@code uq_seat_types_name} uniqueness before insert.
 */
public interface SeatTypeRepository extends JpaRepository<SeatType, Long> {

    boolean existsByName(String name);
}
