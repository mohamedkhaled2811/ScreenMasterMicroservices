package com.gr74.booking.model;

/**
 * Showtime lifecycle. New showtimes start {@link #SCHEDULED}. Persisted as {@code STRING}.
 */
public enum ShowtimeStatus {
    SCHEDULED,
    CANCELLED,
    COMPLETED,
    SOLD_OUT
}
