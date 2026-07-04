package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.booking.model.Theater;

/**
 * Spring Data repository for {@link Theater}. {@code existsByName} is the pre-check half of the
 * uniqueness guard (the {@code uq_theaters_name} constraint is the write half that closes the race).
 */
public interface TheaterRepository extends JpaRepository<Theater, Long> {

    boolean existsByName(String name);
}
