package com.gr74.booking.exception;

/**
 * Base class for deliberate booking errors; carries the code the handler renders.
 */
public class BookingException extends RuntimeException {

    private final transient BookingErrorCode errorCode;

    public BookingException(BookingErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BookingException(BookingErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public BookingErrorCode errorCode() {
        return errorCode;
    }
}
