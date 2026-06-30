package com.gr74.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.catalog.model.Genre;

/**
 * Spring Data repository for {@link Genre}. The key is {@link Long} — the assigned TMDB genre id.
 *
 * <p>Used by the TMDB sync to upsert genres <em>before</em> the movies that reference them (parents
 * before the {@code movie_genres} join rows). Because the id is assigned, {@code save()} on an
 * existing id is an UPDATE, which makes a re-sync idempotent — no duplicate genres.
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {
}
