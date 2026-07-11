package com.gr74.booking.model;

/**
 * Payment outcome tracked on a booking — kept separate from {@link BookingStatus} so "the seats are held"
 * and "the money cleared" can move independently (a booking can be {@code PENDING} with payment
 * {@code PENDING}, then {@code CONFIRMED}/{@code PAID}, or {@code CANCELLED} with payment {@code FAILED}).
 *
 * <p>Persisted as a {@code String} ({@code @Enumerated(EnumType.STRING)}; the monolith stored this ordinal
 * on Booking — §2.4 fix). In this part (no Payment call yet) a new booking is always {@link #PENDING};
 * the Phase-3 saga sets {@link #PAID}/{@link #FAILED} from Payment's response.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED
}
