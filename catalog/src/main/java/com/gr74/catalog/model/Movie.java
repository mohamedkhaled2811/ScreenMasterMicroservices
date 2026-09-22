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
 * A movie in the catalogue. The {@code id} is the TMDB movie id (assigned, not generated).
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

    /** TMDB free-text status (Released, Post Production, ...). */
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

    /** Attach a genre to this movie. */
    public void addGenre(Genre genre) {
        this.genres.add(genre);
    }

    /** Replace this movie's genre links wholesale. */
    public void setGenres(Set<Genre> genres) {
        this.genres = new LinkedHashSet<>(genres);
    }

    /**
     * Apply the mutable detail fields in one place.
     */
    public void applyDetails(java.util.function.Consumer<Details> mutator) {
        Details d = new Details(this);
        mutator.accept(d);
    }

    /** Fluent setter facade over a {@link Movie}'s detail fields. */
    public static final class Details {
        private final Movie m;

        private Details(Movie m) {
            this.m = m;
        }

        public Details title(String v) { m.title = v; return this; }
        public Details originalTitle(String v) { m.originalTitle = v; return this; }
        public Details overview(String v) { m.overview = v; return this; }
        public Details tagline(String v) { m.tagline = v; return this; }
        public Details releaseDate(LocalDate v) { m.releaseDate = v; return this; }
        public Details runtime(Integer v) { m.runtime = v; return this; }
        public Details status(String v) { m.status = v; return this; }
        public Details originalLanguage(String v) { m.originalLanguage = v; return this; }
        public Details popularity(BigDecimal v) { m.popularity = v; return this; }
        public Details voteAverage(BigDecimal v) { m.voteAverage = v; return this; }
        public Details voteCount(Integer v) { m.voteCount = v; return this; }
        public Details posterPath(String v) { m.posterPath = v; return this; }
        public Details backdropPath(String v) { m.backdropPath = v; return this; }
        public Details adult(boolean v) { m.adult = v; return this; }
    }
}
