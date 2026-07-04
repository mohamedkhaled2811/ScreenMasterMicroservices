package com.gr74.booking.model;

import java.time.Instant;

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
 * A screen (auditorium) inside a {@link Theater}. Its name is unique <em>within</em> its theater
 * (two theaters can each have a "Screen 1"), enforced by the {@code uq_screens_name_theater}
 * constraint.
 *
 * <p>The {@link Theater} link is a {@code @ManyToOne(LAZY)} FK — an intra-Booking reference, so it
 * stays a real FK (unlike the cross-service {@code movieId} cut on {@link Showtime}). It is fetched
 * lazily; because we run {@code open-in-view: false}, any code that needs the theater must load it
 * inside a transaction (the service does, when it validates the parent exists). {@code screenType} is
 * a {@link ScreenType} stored as a {@code String} — the schema-§9 fix for the monolith's ordinal enum.
 */
@Entity
@Table(
        name = "screens",
        // Mirrors the Liquibase uq_screens_name_theater — declared here too so Hibernate's schema (used
        // by @DataJpaTest) enforces the same invariant the production DB does.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_screens_name_theater", columnNames = {"name", "theater_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Screen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING) // never ordinal — schema §9 fix
    @Column(name = "screen_type", nullable = false)
    private ScreenType screenType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Screen(String name, ScreenType screenType, Theater theater) {
        this.name = name;
        this.screenType = screenType;
        this.theater = theater;
    }
}
