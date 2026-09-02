package com.gr74.booking.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A user's reservation of one or more seats for a showtime.
 *
 * <p><b>Two cross-service references, both plain ids, no FK</b> (schema §8.2):
 * <ul>
 *   <li>{@code userId} — Identity owns users. A {@link String} because it holds the Keycloak {@code sub}
 *       (a UUID) in Phase 7; today it's the {@code X-User-Id} header value.</li>
 *   <li>{@code movieId} — <em>snapshotted</em> from the showtime at create time. Booking copies it so
 *       "my bookings" can name the movie without re-joining the showtime, and the title is then resolved
 *       live from Catalog by id (the M2 composition). Frozen ids are cheap; the mutable title stays fresh.</li>
 * </ul>
 * {@code showtimeId} is an intra-Booking FK to {@code showtimes.id} (migration 007). Deleting a
 * showtime that still has bookings is rejected ({@code ON DELETE NO ACTION}). {@code totalAmount} is a
 * snapshot of the total at booking time — never recomputed from live prices.
 *
 * <p>Enums are {@code @Enumerated(STRING)} (never ordinal — §2.4 fix). The {@code bookingReference} is
 * unique. Schema owned by Liquibase ({@code ddl-auto=validate}); must match {@code 003-create-bookings.yaml}
 * + {@code 004-alter-bookings-user-id-varchar.yaml}. There is no Payment call here — the saga is Phase 3.
 */
@Entity
@Table(
        name = "bookings",
        uniqueConstraints = @UniqueConstraint(name = "uq_bookings_reference", columnNames = "booking_reference"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_reference", nullable = false, unique = true, length = 20)
    private String bookingReference;

    /** Cross-service reference to Identity's user — the Keycloak {@code sub} (UUID string) from Phase 7. */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "showtime_id", nullable = false)
    private Long showtimeId;

    /** Snapshot of the showtime's {@code movieId} at create time — the cross-service ref to Catalog. */
    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /**
     * ISO-4217 code, snapshotted from the theater at booking time (changeset 009).
     *
     * <p>Frozen alongside {@code totalAmount} for the same reason: the amount charged must not move
     * because someone edited the theater afterwards. Payment reads this pair to decide which gateways
     * can settle the booking — Paymob takes EGP, Stripe test mode takes USD.
     */
    @Column(nullable = false, length = 3)
    private String currency;

    /** The 15-min hold deadline; the Phase-3 sweeper frees seats past this. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The reserved seats. Cascade + orphan-removal so a booking owns its line items: persisting the
     * booking persists its seats, and clearing the list deletes them (used by the Phase-3 compensation).
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<BookingSeat> seats = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Booking(String bookingReference, String userId, Long showtimeId, Long movieId,
            BigDecimal totalAmount, String currency, Instant expiresAt) {
        this.bookingReference = bookingReference;
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.movieId = movieId;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.PENDING;        // a new booking always starts holding its seats
        this.paymentStatus = PaymentStatus.PENDING; // no Payment call yet — the saga sets this in Phase 3
    }

    /** Add a seat to this booking, keeping both sides of the relationship in sync. */
    public void addSeat(BookingSeat seat) {
        seats.add(seat);
        seat.assignTo(this);
    }
}
