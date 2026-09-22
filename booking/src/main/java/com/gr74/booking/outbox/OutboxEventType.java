package com.gr74.booking.outbox;

/**
 * Business facts this service announces through the outbox.
 */
public enum OutboxEventType {

    /** A hold became a sale; Notification sends the booking email off this. */
    BOOKING_CONFIRMED,

    /** Money arrived for a booking that can no longer confirm; Payment refunds off this. */
    BOOKING_CONFIRMATION_REJECTED
}