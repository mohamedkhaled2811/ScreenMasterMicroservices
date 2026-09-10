package com.gr74.payment.exception;

/**
 * The booking exists but cannot be paid for right now.
 *
 * <p>Covers the three guards that all mean "don't open a checkout": the booking is in a
 * non-payable state, its seat hold has lapsed, or its payment is already settled. Each carries a
 * distinct {@link PaymentErrorCode} because a client reacts differently to each — retry with a new
 * booking, show the ticket, or show an error.
 */
public class BookingNotPayableException extends PaymentException {

    public BookingNotPayableException(PaymentErrorCode code, String message) {
        super(code, message);
    }

    /** The seat hold lapsed — the seats may already be resold, so a new booking is required. */
    public static BookingNotPayableException expired(Long bookingId) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_BOOKING_EXPIRED,
                "Booking " + bookingId + " has expired; its seats may have been released. Create a new booking.");
    }

    /** Wrong lifecycle state (already CONFIRMED, CANCELLED, ...). */
    public static BookingNotPayableException notPayable(Long bookingId, String status) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_BOOKING_NOT_PAYABLE,
                "Booking " + bookingId + " is " + status + " and cannot be paid for");
    }

    /** Already settled — creating another session would risk a double charge. */
    public static BookingNotPayableException alreadyPaid(Long bookingId) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_ALREADY_PAID,
                "Booking " + bookingId + " is already paid");
    }
}
