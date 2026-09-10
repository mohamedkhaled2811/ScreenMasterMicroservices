package com.gr74.payment.exception;

/**
 * Booking has no such booking. A definitive answer (Booking returned 404), not an outage — retrying
 * will not help, so this is a 404 to our caller rather than a 503.
 */
public class BookingNotFoundException extends PaymentException {

    public BookingNotFoundException(Long bookingId) {
        super(PaymentErrorCode.PAYMENT_BOOKING_NOT_FOUND, "Booking " + bookingId + " does not exist");
    }
}
