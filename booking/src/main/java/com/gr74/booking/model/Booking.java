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
 * A user's reservation of one or more seats for a showtime. {@code userId} and {@code movieId}
 * are plain ids (no FKs); {@code totalAmount} and {@code currency} are snapshotted at booking time.
 * Enums persist as {@code STRING}; schema is owned by Liquibase.
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

    /** Owner's user id (Identity's Keycloak {@code sub}). */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "showtime_id", nullable = false)
    private Long showtimeId;

    /** Showtime's {@code movieId} copied at create time, so bookings read standalone. */
    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    /**
     * Display-only mirror of Payment's answer; never read for decisions (the seat guard looks at
     * {@code status} only). A late failure arriving after {@code PAID} is dropped, not mirrored.
     */
    @Enumerated(EnumType.STRING) // never ordinal
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /** ISO-4217 code snapshotted from the theater at booking time, alongside {@code totalAmount}. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Hold deadline; expired holds free their seats via a status flip to {@code EXPIRED}. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Reserved seats owned by this booking. Flipping the booking to {@code EXPIRED} releases the
     * seats; rows are kept as the audit trail.
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
        this.status = BookingStatus.PENDING;        // new bookings start holding their seats
        this.paymentStatus = PaymentStatus.PENDING; // no Payment outcome yet
    }

    /** Add a seat, keeping both sides of the relationship in sync. */
    public void addSeat(BookingSeat seat) {
        seats.add(seat);
        seat.assignTo(this);
    }
}
