package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Theater;

/**
 * Spring Data repository for {@link Theater}.
 */
public interface TheaterRepository extends JpaRepository<Theater, Long>, JpaSpecificationExecutor<Theater> {

    boolean existsByName(String name);
}
