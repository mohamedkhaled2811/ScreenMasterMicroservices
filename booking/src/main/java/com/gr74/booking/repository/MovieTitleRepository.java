package com.gr74.booking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.booking.model.MovieTitle;

/**
 * Spring Data repository for the {@link MovieTitle} read model. The key is {@link Long} — the assigned
 * TMDB movie id.
 *
 * <p>{@link #findAllById(Iterable)} (inherited) is the local "JOIN": way B resolves a page of bookings'
 * distinct movie ids to titles with a single {@code WHERE id IN (…)} against this local table — no
 * Catalog call at read time. That's the whole point of the read model.
 */
public interface MovieTitleRepository extends JpaRepository<MovieTitle, Long> {

    /** Batch-load the cached titles for a page's distinct movie ids — the local join for way B. */
    List<MovieTitle> findByIdIn(Collection<Long> ids);
}
