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
 * Synchronous read of Booking payability. Fails closed: no answer means no checkout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingClient {

    private final RestClient bookingRestClient;

    /** Fetches the payability slice for a booking. */
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
            throw e;
        } catch (RestClientException e) {
            log.warn("Booking service unreachable while verifying booking {}: {}", bookingId, e.getMessage());
            throw new BookingServiceUnavailableException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            // No healthy Booking instance in the registry — treat as an outage (503).
            log.warn("No Booking instance available while verifying booking {}: {}", bookingId, e.getMessage());
            throw new BookingServiceUnavailableException(e.getMessage(), e);
        }
    }
}
