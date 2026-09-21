package com.gr74.booking.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A booking's hold converted into a sale — raised in-JVM by
 * {@link com.gr74.booking.service.BookingConfirmer}, written to the outbox in the same transaction,
 * and published to the broker by the outbox relay on {@code booking-confirmed-key}.
 *
 * <p>{@code userId} rides along because Notification needs it and must not call back for it;
 * {@code eventId} is the <b>outbox row id</b>, injected by the relay at publish time — stable across
 * redeliveries, which is what makes the consumer's dedupe fire at all (a fresh id per publish would
 * make every redelivery look like a new event).
 *
 * <h2>Why this event is a document, not a notification</h2>
 *
 * <p>Most of the fields below exist so Notification can render a <b>ticket</b>. It would have been
 * leaner to keep the event at four fields and let Notification call back for the rest — but a ticket
 * needs seats, a showtime, a theater, a screen, a movie title and a poster, which live across Booking
 * <em>and</em> Catalog. Fanning out for them at send time would put two synchronous dependencies on
 * the consume path that Phase 4 exists to remove, and — the real objection — it would re-read
 * <em>live</em> state. A ticket is a historical record: it must say what was true when the booking
 * confirmed. If a theater is renamed next year, the ticket already sent does not retroactively change,
 * and a redelivery re-renders byte-identically. Snapshotting is the only way to promise that.
 *
 * <p>This is the repo's own standing rule (CLAUDE.md: <i>"Snapshot immutable facts into the
 * consumer"</i>) applied to an event rather than a row — the same reason {@code booking_seats} already
 * freezes seat price and type name.
 *
 * <p><b>The cost, stated plainly:</b> the event is now a large contract, and Notification's copy of
 * this record must be kept in step by hand (no shared jar, by design). That is the trade accepted for
 * a consumer with zero synchronous dependencies on Booking or Catalog.
 *
 * @param eventId          the outbox row id — stable across redeliveries (contrast the old random UUID)
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, printed on the ticket
 * @param userId           opaque Identity reference, off the booking row — Notification resolves it to
 *                         an email address via Keycloak (the one fact deliberately NOT snapshotted,
 *                         because an address must be current rather than historical)
 * @param movieId          Catalog's movie id, for correlation and logs
 * @param movieTitle       the movie's title as cached in Booking's read model; null if unresolvable —
 *                         the ticket renders without it rather than failing
 * @param posterPath       TMDB artwork PATH ("/abc.jpg"), not a URL: the CDN host and image size are
 *                         Notification's rendering decisions. Null when there is no artwork
 * @param showtimeStartsAt when the screening begins, as a UTC instant composed from the showtime's
 *                         local date + time — the consumer formats it, and an instant cannot be
 *                         misread the way a bare local time can
 * @param theaterName      which cinema, snapshotted
 * @param screenName       which auditorium, snapshotted
 * @param seats            the ticket's line items — one per reserved seat
 * @param totalAmount      what was charged, off the booking row
 * @param currency         ISO-4217 code for {@code totalAmount}
 * @param occurredAt       when the confirm committed (Booking's clock)
 */
public record BookingConfirmed(
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
