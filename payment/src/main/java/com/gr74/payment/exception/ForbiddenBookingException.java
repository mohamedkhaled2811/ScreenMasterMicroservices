package com.gr74.payment.exception;

/**
 * The caller asked to pay for someone else's booking.
 *
 * <p>Checked even though the booking id is not secret: without it, anyone could enumerate ids and
 * open checkout sessions against other people's bookings. The message deliberately does not confirm
 * whose it is.
 */
public class ForbiddenBookingException extends PaymentException {

    public ForbiddenBookingException(Long bookingId) {
        super(PaymentErrorCode.PAYMENT_FORBIDDEN, "Booking " + bookingId + " does not belong to this user");
    }
}
