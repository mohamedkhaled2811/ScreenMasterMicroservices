package com.gr74.booking.exception;

/**
 * Catalog has no movie for the given id; maps to {@code BOOKING_MOVIE_NOT_FOUND} (404).
 */
public class MovieNotInCatalogException extends BookingException {

    public MovieNotInCatalogException(long movieId) {
        super(BookingErrorCode.BOOKING_MOVIE_NOT_FOUND,
                "Catalog has no movie with id=" + movieId);
    }
}
