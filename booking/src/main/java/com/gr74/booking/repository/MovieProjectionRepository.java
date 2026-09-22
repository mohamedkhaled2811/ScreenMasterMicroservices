package com.gr74.booking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.booking.model.MovieProjection;

/**
 * Spring Data repository for the {@link MovieProjection} read model (keyed by movie id).
 */
public interface MovieProjectionRepository extends JpaRepository<MovieProjection, Long> {

    /** Batch-load cached titles for a page's distinct movie ids (the local join). */
    List<MovieProjection> findByIdIn(Collection<Long> ids);
}
