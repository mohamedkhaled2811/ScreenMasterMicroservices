package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.SeatType;

/**
 * Spring Data repository for {@link SeatType}. {@code existsByName} pre-checks the
 * {@code uq_seat_types_name} uniqueness before insert.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so {@code GET /seat-types} is paged and filterable like
 * every other listing — no exemption, per the convention. See
 * {@code docs/concepts/pagination-and-filtering.md}.
 */
public interface SeatTypeRepository extends JpaRepository<SeatType, Long>, JpaSpecificationExecutor<SeatType> {

    boolean existsByName(String name);
}
