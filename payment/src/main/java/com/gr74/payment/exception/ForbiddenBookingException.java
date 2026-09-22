package com.gr74.payment.exception;

/**
 * The caller asked to pay for someone else's booking (403).
 */
public class ForbiddenBookingException extends PaymentException {

    public ForbiddenBookingException(Long bookingId) {
        super(PaymentErrorCode.PAYMENT_FORBIDDEN, "Booking " + bookingId + " does not belong to this user");
    }
}
