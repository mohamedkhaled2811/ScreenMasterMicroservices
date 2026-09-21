package com.gr74.notification.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A booking's hold converted into a sale — Notification's OWN copy of Booking's
 * {@code BookingConfirmed} wire shape (cross-service types are duplicated by design: no shared jar,
 * so neither service's deploy can break the other's compile).
 *
 * <p><b>This event is a document.</b> Almost every field below exists so this service can render a
 * ticket without calling anyone: the seats, the showtime, the theater, the screen, the movie title
 * and its poster are all <em>snapshots</em> Booking took inside the confirm transaction. That is what
 * lets this consumer have zero synchronous dependencies on Booking or Catalog — Catalog can be down
 * for a day and tickets still send — and it is what makes a re-render years later print exactly what
 * the first send printed. A ticket is a historical record, not a view of current state.
 *
 * <p>The one fact deliberately NOT snapshotted is the recipient's email address, which is resolved
 * from Keycloak at send time ({@code KeycloakUserClient}) because an address must be <em>current</em>
 * rather than historical.
 *
 * <p><b>Fields are matched by name during JSON deserialization</b>, and unknown properties are
 * ignored — so Booking may add fields without breaking this consumer. A field Booking <em>removes</em>
 * or renames, however, silently arrives as null: that is the cost of the duplicated contract, and the
 * reason the template renders defensively around every optional field.
 *
 * @param eventId          the publisher's outbox row id — STABLE across redeliveries, which is what
 *                         makes consumer dedupe possible (the {@code processed_events} PK)
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, printed on the ticket
 * @param userId           opaque Identity reference — resolved to an email via Keycloak
 * @param movieId          Catalog's movie id, for correlation and logs
 * @param movieTitle       the movie's title; null if Booking could not resolve it (render without)
 * @param posterPath       TMDB artwork PATH ("/abc.jpg") — NOT a URL. The CDN host and image size are
 *                         this service's rendering decision, composed by {@code NotificationProps.Image}
 * @param showtimeStartsAt when the screening begins, as an instant; formatted for display here
 * @param theaterName      which cinema, snapshotted at confirm time
 * @param screenName       which auditorium, snapshotted at confirm time
 * @param seats            the ticket's line items — one card per seat in the rendered email
 * @param totalAmount      what was charged
 * @param currency         ISO-4217 code for {@code totalAmount}
 * @param occurredAt       when the confirm committed (Booking's clock)
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
