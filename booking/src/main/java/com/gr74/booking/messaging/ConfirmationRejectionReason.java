package com.gr74.booking.messaging;

/**
 * Why a payment could not confirm its booking; Payment refunds off this.
 */
public enum ConfirmationRejectionReason {
    EXPIRED,
    CANCELLED
}
