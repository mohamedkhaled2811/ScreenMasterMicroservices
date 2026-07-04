package com.gr74.booking.exception;

/**
 * Thrown when an inventory or scheduling resource is looked up by an id that has no row. The specific
 * {@link BookingErrorCode} (theater / screen / seat / seat-type / showtime not found) is supplied by
 * the caller, so one class covers every "parent doesn't exist" case in this part without a subclass
 * per entity. All map to HTTP 404 via {@link GlobalExceptionHandler}.
 *
 * <p>Factory methods keep the call sites intention-revealing and the messages consistent
 * ({@code ResourceNotFoundException.theater(id)}).
 */
public class ResourceNotFoundException extends BookingException {

    public ResourceNotFoundException(BookingErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static ResourceNotFoundException theater(long id) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_THEATER_NOT_FOUND,
                "Theater not found for id=" + id);
    }

    public static ResourceNotFoundException screen(long id) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_SCREEN_NOT_FOUND,
                "Screen not found for id=" + id);
    }

    public static ResourceNotFoundException screenInTheater(long screenId, long theaterId) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_SCREEN_NOT_FOUND,
                "Screen id=" + screenId + " not found under theater id=" + theaterId);
    }

    public static ResourceNotFoundException seat(long id) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_SEAT_NOT_FOUND,
                "Seat not found for id=" + id);
    }

    public static ResourceNotFoundException seatType(long id) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_SEAT_TYPE_NOT_FOUND,
                "Seat type not found for id=" + id);
    }

    public static ResourceNotFoundException showtime(long id) {
        return new ResourceNotFoundException(BookingErrorCode.BOOKING_SHOWTIME_NOT_FOUND,
                "Showtime not found for id=" + id);
    }
}
