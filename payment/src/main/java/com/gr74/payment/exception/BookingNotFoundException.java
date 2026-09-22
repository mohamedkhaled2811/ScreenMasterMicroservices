package com.gr74.payment.exception;

/**
 * Booking has no such booking (404); retrying will not help.
 */
public class BookingNotFoundException extends PaymentException {

    public BookingNotFoundException(Long bookingId) {
        super(PaymentErrorCode.PAYMENT_BOOKING_NOT_FOUND, "Booking " + bookingId + " does not exist");
    }
}
