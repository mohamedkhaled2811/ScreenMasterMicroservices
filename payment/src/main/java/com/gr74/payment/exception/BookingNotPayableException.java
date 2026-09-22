package com.gr74.payment.exception;

/**
 * The booking exists but cannot be paid for right now (expired, wrong state, or already paid).
 */
public class BookingNotPayableException extends PaymentException {

    public BookingNotPayableException(PaymentErrorCode code, String message) {
        super(code, message);
    }

    /** Seat hold lapsed; a new booking is required. */
    public static BookingNotPayableException expired(Long bookingId) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_BOOKING_EXPIRED,
                "Booking " + bookingId + " has expired; its seats may have been released. Create a new booking.");
    }

    /** Wrong lifecycle state for payment. */
    public static BookingNotPayableException notPayable(Long bookingId, String status) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_BOOKING_NOT_PAYABLE,
                "Booking " + bookingId + " is " + status + " and cannot be paid for");
    }

    /** Already settled; another session would risk double charge. */
    public static BookingNotPayableException alreadyPaid(Long bookingId) {
        return new BookingNotPayableException(PaymentErrorCode.PAYMENT_ALREADY_PAID,
                "Booking " + bookingId + " is already paid");
    }
}
