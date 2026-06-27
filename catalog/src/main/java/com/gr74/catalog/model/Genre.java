package com.gr74.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A movie genre (Action, Drama, ...). The {@code id} is the TMDB genre id — <em>assigned</em>, not
 * generated (no {@code @GeneratedValue}): a stable, externally-sourced key, which is exactly why
 * Catalog is a clean service boundary.
 *
 * <p>Schema is owned by Liquibase, not Hibernate ({@code ddl-auto=validate}); this entity must stay
 * in sync with {@code db/changelog/changes/001-create-movies-genres.yaml} or startup fails.
 */
@Entity
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class Genre {

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public Genre(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
