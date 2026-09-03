package com.gr74.payment.exception;

/**
 * Booking could not be reached, so the amount could not be verified.
 *
 * <p>We <b>fail closed</b>: opening a checkout session for an amount we could not confirm is worse
 * than an outage, because it charges a real user a number we invented. Contrast Booking's own
 * {@code titlesByIds}, which degrades to null titles — a read is more useful partial than absent,
 * but a charge is not.
 */
public class BookingServiceUnavailableException extends PaymentException {

    public BookingServiceUnavailableException(String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_BOOKING_SERVICE_UNAVAILABLE,
                "Could not verify the booking with the Booking service: " + message, cause);
    }
}
