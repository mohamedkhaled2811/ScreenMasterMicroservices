package com.gr74.payment.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gr74.payment.exception.BookingNotFoundException;
import com.gr74.payment.exception.BookingServiceUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment's synchronous window into Booking — the one cross-service read on the payment write path.
 *
 * <p>It exists because Payment must never trust the client for the amount. A browser that could name
 * its own price would be the whole security model gone, so the amount (and the booking's state, owner
 * and hold deadline) comes from the service that owns it.
 *
 * <p><b>This fails closed.</b> Booking unreachable → {@link BookingServiceUnavailableException} (503),
 * never a default or a guess: opening a real checkout for an amount we could not verify would charge a
 * real user a number we invented. That is the opposite of Booking's own {@code titlesByIds}, which
 * degrades to null titles — a read is more useful partial than absent, but a charge is not. The two
 * sitting side by side is the lesson: the right failure policy depends on what the call is for.
 *
 * <p>The 404/outage split mirrors {@code CatalogClient.verifyMovieExists}: "no such booking" is a
 * definitive answer that retrying will not change (404), while an outage might resolve later (503).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingClient {

    private final RestClient bookingRestClient;

    /**
     * Fetch the payability slice for a booking.
     *
     * @throws BookingNotFoundException            Booking answered 404 — a definitive "no such booking"
     * @throws BookingServiceUnavailableException  we could not get a trustworthy answer
     */
    public BookingPayability fetchPayability(long bookingId) {
        try {
            BookingPayability payability = bookingRestClient.get()
                    .uri("/bookings/{id}/payability", bookingId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw new BookingNotFoundException(bookingId);
                        }
                        throw new BookingServiceUnavailableException(
                                "Booking returned " + response.getStatusCode(), null);
                    })
                    .body(BookingPayability.class);

            if (payability == null) {
                throw new BookingServiceUnavailableException("Booking returned an empty body", null);
            }
            return payability;

        } catch (BookingNotFoundException | BookingServiceUnavailableException e) {
            throw e; // already coded — don't re-wrap
        } catch (RestClientException e) {
            // Transport failure or timeout: we genuinely do not know the booking's state.
            log.warn("Booking service unreachable while verifying booking {}: {}", bookingId, e.getMessage());
            throw new BookingServiceUnavailableException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            // Spring Cloud LoadBalancer throws a bare IllegalStateException ("No instances available
            // for booking") when Eureka knows of no healthy instance — i.e. Booking is down or has not
            // registered yet. That is exactly an outage, but it is NOT a RestClientException, so
            // without this branch it escaped as an opaque 500 instead of our coded 503. Caught live
            // against a real registry; the unit tests mocked the client and never saw it.
            log.warn("No Booking instance available while verifying booking {}: {}", bookingId, e.getMessage());
            throw new BookingServiceUnavailableException(e.getMessage(), e);
        }
    }
}
