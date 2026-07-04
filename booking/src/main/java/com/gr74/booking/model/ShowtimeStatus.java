package com.gr74.booking.model;

/**
 * Lifecycle of a showtime.
 *
 * <p>Persisted as a {@code String} ({@code @Enumerated(EnumType.STRING)}) — the monolith already
 * stored this one as a String, and we keep that (never ordinal; see the project convention). A new
 * showtime starts {@link #SCHEDULED}; the Phase-3 saga / sweeper may drive it to the others.
 */
public enum ShowtimeStatus {
    SCHEDULED,
    CANCELLED,
    COMPLETED,
    SOLD_OUT
}
