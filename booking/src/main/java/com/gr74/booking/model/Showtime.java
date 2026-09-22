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
 * A scheduled screening of a movie on a {@link Screen}. {@code movieId} is a plain id with no FK
 * (Catalog owns movies); {@code screen} is a real intra-service FK. The slot
 * {@code (screen, movieId, showDate, showTime)} is unique.
 */
@Entity
@Table(
        name = "showtimes",
        // Declared here too so the @DataJpaTest schema enforces the same slot invariant.
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

    /** Catalog movie id (plain column, no relationship and no FK). */
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
