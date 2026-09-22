package com.gr74.booking.model;

/**
 * Payment outcome on a booking, tracked separately from {@link BookingStatus}. Persisted as {@code STRING}.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED
}
