package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.SeatType;

/**
 * Spring Data repository for {@link SeatType}.
 */
public interface SeatTypeRepository extends JpaRepository<SeatType, Long>, JpaSpecificationExecutor<SeatType> {

    boolean existsByName(String name);
}
