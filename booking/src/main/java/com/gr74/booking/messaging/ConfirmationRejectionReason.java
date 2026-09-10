package com.gr74.booking.messaging;

/**
 * Why a {@code PaymentSucceeded} could not confirm its booking — the compensation trigger
 * vocabulary Payment's 3.5 listener branches on.
 *
 * <p>Only two values exist because they are the only terminal states a hold can be in when money
 * arrives late: the hold lapsed on its own ({@code EXPIRED}) or a human cancelled it
 * ({@code CANCELLED}). Either way the seats are gone and the money must come back.
 */
public enum ConfirmationRejectionReason {
    EXPIRED,
    CANCELLED
}
