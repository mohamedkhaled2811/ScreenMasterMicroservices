package com.gr74.notification.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Confirmed booking carrying snapshotted ticket facts; renders the ticket without further lookups.
 *
 * @param eventId          dedupe key, stable across redeliveries
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, printed on the ticket
 * @param userId           Identity reference — resolved to an email via Keycloak
 * @param movieId          Catalog's movie id, for correlation and logs
 * @param movieTitle       the movie's title; null if it could not be resolved
 * @param posterPath       TMDB artwork path ("/abc.jpg"), not a URL
 * @param showtimeStartsAt when the screening begins
 * @param theaterName      which cinema, snapshotted at confirm time
 * @param screenName       which auditorium, snapshotted at confirm time
 * @param seats            the ticket's line items
 * @param totalAmount      what was charged
 * @param currency         ISO-4217 code for {@code totalAmount}
 * @param occurredAt       when the confirm committed
 */
public record BookingConfirmedEvent(
        long eventId,
        long bookingId,
        String bookingReference,
        String userId,
        Long movieId,
        String movieTitle,
        String posterPath,
        Instant showtimeStartsAt,
        String theaterName,
        String screenName,
        List<TicketSeat> seats,
        BigDecimal totalAmount,
        String currency,
        Instant occurredAt) {
}
