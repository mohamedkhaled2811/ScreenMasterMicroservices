package com.gr74.booking.model;

/**
 * Booking lifecycle. New bookings start {@link #PENDING} holding their seats. Persisted as {@code STRING}.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
