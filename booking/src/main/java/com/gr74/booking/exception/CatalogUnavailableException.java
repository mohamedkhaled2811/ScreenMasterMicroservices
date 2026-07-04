package com.gr74.booking.exception;

/**
 * Thrown when Booking could not reach Catalog to validate a {@code movieId} — connection refused,
 * timeout, a 5xx from Catalog, or any non-404 transport failure. Maps to
 * {@link BookingErrorCode#BOOKING_CATALOG_UNAVAILABLE} (HTTP 503).
 *
 * <p>This exception <em>is</em> the cost of choosing plan option 5C (validate on the write path):
 * because showtime creation now depends on Catalog being up, a Catalog outage turns into a 503 on
 * {@code POST /showtimes}. Distinct from {@link MovieNotInCatalogException} (a definitive "no such
 * movie") so callers can retry an outage but must fix a bad id. See
 * {@code docs/concepts/sync-vs-async-comms.md} (temporal coupling) — this is the very coupling the
 * recommended 5C option avoided, made explicit here.
 */
public class CatalogUnavailableException extends BookingException {

    public CatalogUnavailableException(long movieId, Throwable cause) {
        super(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE,
                "Could not reach Catalog to validate movieId=" + movieId, cause);
    }
}
