package com.gr74.booking.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Booking's local read copy of a Catalog movie title, keyed by movie id. The id is assigned (mirrored,
 * not generated), so event redelivery upserts the same row. {@code updatedAt} is the source row's
 * timestamp and the baseline for the ordering guard; {@code posterPath} is a TMDB path, never a full URL.
 */
@Entity
@Table(name = "movie_projections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieProjection {

    @Id
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    /** TMDB poster path ("/abc123.jpg"); null when the movie has no artwork. */
    @Column(name = "poster_path", length = 255)
    private String posterPath;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public MovieProjection(Long id, String title, String posterPath, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.posterPath = posterPath;
        this.updatedAt = updatedAt;
    }

    /** Apply a newer title to this row (callers check the ordering guard first). */
    public void apply(String title, String posterPath, Instant updatedAt) {
        this.title = title;
        this.posterPath = posterPath;
        this.updatedAt = updatedAt;
    }
}
