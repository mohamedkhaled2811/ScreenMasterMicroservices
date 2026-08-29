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
 * Booking's local <b>read model</b> of a Catalog movie title — one row per movie id Booking has ever
 * needed to name. It is a projection Booking <em>owns a copy of</em>, not the source of truth: Catalog
 * owns movies; this table is kept up to date by consuming {@code MovieUpserted} events (and seeded on
 * demand by lazy backfill). See {@code docs/concepts/cqrs-read-model.md}.
 *
 * <p><b>{@code id} is an assigned PK</b> — the TMDB movie id, the same key Catalog owns and the value
 * snapshotted onto {@code bookings.movie_id}. No {@code @GeneratedValue}: we're mirroring an external id,
 * not minting one. That also makes the consumer's write naturally idempotent — {@code save(id)} on an
 * existing row is an UPDATE, so a redelivered event re-writes the same row instead of duplicating it.
 *
 * <p>{@code updatedAt} is the <em>source row's</em> last-modified time as carried on the event, and it is
 * the baseline for the consumer's ordering guard (drop an event whose timestamp is {@code <=} this one).
 * It is nullable: a row created by lazy backfill (a Catalog fetch, not an event) has no event timestamp,
 * and a {@code null} baseline means "the next real event wins".
 *
 * <p>Schema owned by Liquibase ({@code ddl-auto=validate}); must match {@code 005-create-movie-titles.yaml}.
 * No auditing listener here — {@code updatedAt} is the <em>upstream</em> timestamp we're told, not our own
 * write time, so it must never be overwritten by a local audit hook.
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

    @Column(name = "updated_at")
    private Instant updatedAt;

    public MovieProjection(Long id, String title, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.updatedAt = updatedAt;
    }

    /**
     * Apply a newer title to this cached row. Callers must have already checked the ordering guard — this
     * method just performs the write; it does not itself decide whether the incoming event is newer.
     */
    public void apply(String title, Instant updatedAt) {
        this.title = title;
        this.updatedAt = updatedAt;
    }
}
