package com.gr74.catalog.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A movie in the catalogue. The {@code id} is the TMDB movie id — <em>assigned</em>, not generated
 * (no {@code @GeneratedValue}): a stable, externally-sourced key that other services reference by id.
 *
 * <p>The columns mirror TMDB's {@code GET /movie/{id}} (movie-details) response so the Phase-2 sync
 * job fills them with no further migration. {@code status} is a plain {@link String}, not a JPA enum,
 * because it's a free-text upstream value (Released, Post Production, ...) and an unexpected value
 * must not break persistence.
 *
 * <p>Genres are a {@link ManyToMany} over the {@code movie_genres} join table — an intra-Catalog FK,
 * fetched eagerly-on-demand via the repository's fetch query (we run with
 * {@code open-in-view: false}, so the collection is loaded inside the service transaction, not
 * lazily during JSON serialization). The schema is owned by Liquibase ({@code ddl-auto=validate}):
 * this entity must match {@code 001-create-movies-genres.yaml} or the app refuses to boot.
 */
@Entity
@Table(name = "movies")
@EntityListeners(AuditingEntityListener.class) // populates the @CreatedDate / @LastModifiedDate fields
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class Movie {

    @Id
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(columnDefinition = "TEXT")
    private String overview;

    private String tagline;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    private Integer runtime;

    /** TMDB free-text status (Released, Post Production, ...). Deliberately a String, not an enum. */
    private String status;

    @Column(name = "original_language")
    private String originalLanguage;

    private BigDecimal popularity;

    @Column(name = "vote_average")
    private BigDecimal voteAverage;

    @Column(name = "vote_count")
    private Integer voteCount;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "backdrop_path")
    private String backdropPath;

    @Column(nullable = false)
    private boolean adult;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new LinkedHashSet<>();

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Movie(Long id, String title) {
        this.id = id;
        this.title = title;
    }

    /** Attach a genre to this movie (maintains the owning side of the {@code movie_genres} join). */
    public void addGenre(Genre genre) {
        this.genres.add(genre);
    }
}
