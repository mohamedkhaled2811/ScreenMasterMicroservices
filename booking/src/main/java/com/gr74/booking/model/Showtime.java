package com.gr74.booking.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A scheduled screening: a movie shown on a {@link Screen} at a date + time, for a {@code basePrice}.
 *
 * <p><b>This is where the cross-service cut lives.</b> In the monolith {@code Showtime} had a JPA
 * {@code @ManyToOne} to {@code Movie}; Catalog now owns movies in its own database, so here
 * {@code movieId} is a plain {@code Long} column with <em>no</em> relationship and <em>no</em> FK
 * (schema doc §8.2). Referential integrity for {@code movieId} is the application's job now — we
 * validate it with a synchronous Catalog call at create time (plan option 5C), but the database
 * itself does not enforce it.
 *
 * <p>The {@link Screen} link, by contrast, is a real intra-Booking {@code @ManyToOne(LAZY)} FK. The
 * slot {@code (screen, movieId, showDate, showTime)} is unique ({@code uq_showtimes_slot}).
 * {@code status} is a {@link ShowtimeStatus} stored as a {@code String}. Schema owned by Liquibase
 * ({@code ddl-auto=validate}); must match {@code 002-create-showtimes.yaml}.
 */
@Entity
@Table(
        name = "showtimes",
        // Mirrors the Liquibase uq_showtimes_slot so Hibernate's schema (@DataJpaTest) enforces the
        // same "one movie per screen per date+time" slot invariant. movie_id is part of the unique key
        // but NOT a FK — the cross-service cut.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_showtimes_slot",
                columnNames = {"screen_id", "movie_id", "show_date", "show_time"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Cross-service reference to Catalog's movie — a bare id, not a {@code @ManyToOne}. This is the FK
     * that was cut when movies moved to their own service.
     */
    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false)
    private Screen screen;

    @Column(name = "show_date", nullable = false)
    private LocalDate showDate;

    @Column(name = "show_time", nullable = false)
    private LocalTime showTime;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false)
    private ShowtimeStatus status;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Showtime(Long movieId, Screen screen, LocalDate showDate, LocalTime showTime, BigDecimal basePrice) {
        this.movieId = movieId;
        this.screen = screen;
        this.showDate = showDate;
        this.showTime = showTime;
        this.basePrice = basePrice;
        this.status = ShowtimeStatus.SCHEDULED; // a new showtime is always scheduled
    }
}
