package com.gr74.booking.exception;

/**
 * Thrown when Catalog is reached successfully but reports it has no movie for the {@code movieId} a
 * showtime-create request supplied — i.e. Catalog answered "404, no such movie". Maps to
 * {@link BookingErrorCode#BOOKING_MOVIE_NOT_FOUND} (HTTP 404).
 *
 * <p>This is the synchronous validation of the cross-service cut (plan option 5C, chosen path): the
 * database can't enforce {@code movie_id} referential integrity because Catalog owns movies in another
 * database, so we ask Catalog at create time. Kept distinct from {@link CatalogUnavailableException}
 * ("couldn't reach Catalog") so a bad id and an outage aren't conflated.
 */
public class MovieNotInCatalogException extends BookingException {

    public MovieNotInCatalogException(long movieId) {
        super(BookingErrorCode.BOOKING_MOVIE_NOT_FOUND,
                "Catalog has no movie with id=" + movieId);
    }
}
