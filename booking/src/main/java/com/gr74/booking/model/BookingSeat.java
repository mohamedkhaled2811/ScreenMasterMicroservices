package com.gr74.booking.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One reserved seat within a {@link Booking} — a line item.
 *
 * <p>{@code seatId} is an intra-Booking reference to the reserved {@code Seat}, kept a plain column (the
 * seat catalogue is stable; a relation isn't needed to hold a reservation). The {@code seatPrice} and
 * {@code seatTypeName} are <em>snapshots</em> taken at booking time (schema §8.2): the price is
 * {@code showtime.basePrice × seatType.priceMultiplier} frozen at purchase, so a later multiplier change
 * never rewrites an existing booking's line item, and the type name is copied so the booking reads
 * standalone. {@code (booking, seat)} is unique — a seat appears at most once per booking.
 *
 * <p>Schema owned by Liquibase ({@code ddl-auto=validate}); must match {@code 003-create-booking-seats}.
 */
@Entity
@Table(
        name = "booking_seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_booking_seats_booking_seat",
                columnNames = {"booking_id", "seat_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "seat_price", nullable = false)
    private BigDecimal seatPrice;

    @Column(name = "seat_type_name", nullable = false, length = 60)
    private String seatTypeName;

    public BookingSeat(Long seatId, BigDecimal seatPrice, String seatTypeName) {
        this.seatId = seatId;
        this.seatPrice = seatPrice;
        this.seatTypeName = seatTypeName;
    }

    /** Set the owning booking — called by {@link Booking#addSeat(BookingSeat)} to keep both sides in sync. */
    void assignTo(Booking booking) {
        this.booking = booking;
    }
}
