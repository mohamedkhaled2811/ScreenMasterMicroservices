package com.gr74.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Theater;

/**
 * Spring Data repository for {@link Theater}. {@code existsByName} is the pre-check half of the
 * uniqueness guard (the {@code uq_theaters_name} constraint is the write half that closes the race).
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the dynamic, paged {@code GET /theaters} filter can run
 * a composed {@link org.springframework.data.jpa.domain.Specification} with paging/sorting — a plain
 * to-one entity with no collection to fetch, so {@code findAll(spec, pageable)} pages correctly in SQL
 * (no in-memory {@code LIMIT}). See {@code docs/concepts/pagination-and-filtering.md}.
 */
public interface TheaterRepository extends JpaRepository<Theater, Long>, JpaSpecificationExecutor<Theater> {

    boolean existsByName(String name);
}
