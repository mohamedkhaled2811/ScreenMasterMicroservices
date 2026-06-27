package com.gr74.catalog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.catalog.model.Movie;

/**
 * Spring Data repository for {@link Movie}. The key is {@link Long} — the assigned TMDB movie id.
 *
 * <p>{@link #findById(Object)} is overridden only to attach an {@link EntityGraph} so the
 * {@code genres} collection is fetched in the <em>same</em> query (a join), not lazily. We run with
 * {@code open-in-view: false}, so a lazy {@code genres} would otherwise throw
 * {@code LazyInitializationException} when the DTO mapper reads it after the transaction closes —
 * and an {@code EntityGraph} also avoids the N+1 a per-row lazy load would cause.
 */
public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Override
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findById(Long id);
}
