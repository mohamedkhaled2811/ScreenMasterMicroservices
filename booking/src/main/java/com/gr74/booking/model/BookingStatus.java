package com.gr74.booking.model;

/**
 * Lifecycle of a booking.
 *
 * <p>Persisted as a {@code String} ({@code @Enumerated(EnumType.STRING)}; never ordinal — the monolith's
 * ordinal enums were a flagged fragility, schema §2.4). A booking is created {@link #PENDING} holding its
 * seats; the Phase-3 saga drives it to {@link #CONFIRMED} on payment approval or {@link #CANCELLED} on
 * decline/compensation, and the Phase-3 expiry sweeper moves a stale hold to {@link #EXPIRED} and frees
 * the seats. In this part (minimal create, no saga) a booking only ever reaches {@link #PENDING}.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
