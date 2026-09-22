package com.gr74.booking.exception;

/**
 * Catalog could not be reached for movie validation; maps to {@code BOOKING_CATALOG_UNAVAILABLE} (503).
 */
public class CatalogUnavailableException extends BookingException {

    public CatalogUnavailableException(long movieId, Throwable cause) {
        super(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE,
                "Could not reach Catalog to validate movieId=" + movieId, cause);
    }
}
