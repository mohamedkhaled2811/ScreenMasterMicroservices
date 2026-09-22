package com.gr74.payment.exception;

/**
 * Booking could not be reached, so the amount could not be verified. Fails closed (503).
 */
public class BookingServiceUnavailableException extends PaymentException {

    public BookingServiceUnavailableException(String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_BOOKING_SERVICE_UNAVAILABLE,
                "Could not verify the booking with the Booking service: " + message, cause);
    }
}
