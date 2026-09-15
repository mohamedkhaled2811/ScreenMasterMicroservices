package com.gr74.notification.messaging;

/**
 * Why a {@code BookingConfirmationRejected} could not confirm its booking — the compensation
 * vocabulary the notification's "refunded" email reads off.
 *
 * <p>Duplicated per service rather than shared via a jar: this is Booking's
 * {@code ConfirmationRejectionReason} with the same two values, so the JSON string on the wire
 * maps 1:1. Only two values exist because they are the only terminal states a hold can be in when
 * money arrives late: the hold lapsed on its own ({@code EXPIRED}) or a human cancelled it
 * ({@code CANCELLED}).
 */
public enum ConfirmationRejectionReason {
    EXPIRED,
    CANCELLED
}