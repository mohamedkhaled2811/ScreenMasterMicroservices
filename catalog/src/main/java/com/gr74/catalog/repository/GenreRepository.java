package com.gr74.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.catalog.model.Genre;

/** Spring Data repository for {@link Genre}. */
public interface GenreRepository extends JpaRepository<Genre, Long> {
}
