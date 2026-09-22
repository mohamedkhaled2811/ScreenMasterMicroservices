package com.gr74.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.messaging.BookingConfirmationRejected;
import com.gr74.booking.messaging.BookingConfirmed;
import com.gr74.booking.messaging.ConfirmationRejectionReason;
import com.gr74.booking.messaging.PaymentFailedEvent;
import com.gr74.booking.messaging.PaymentSucceededEvent;
import com.gr74.booking.messaging.TicketSeat;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;
import com.gr74.booking.model.Seat;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.outbox.OutboxEventType;
import com.gr74.booking.outbox.OutboxMessage;
import com.gr74.booking.outbox.OutboxMessageRepository;
import com.gr74.booking.client.CatalogClient.MovieProjectionData;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.repository.SeatRepository;
import com.gr74.booking.repository.ShowtimeRepository;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies Payment's outcomes to Booking's holds — the saga step (BUILD_PLAN 3.3), behind the thin
 * {@link com.gr74.booking.messaging.PaymentEventListener} shell.
 *
 * <p>Kept a plain {@code @Transactional} service (not the {@code @RabbitListener} itself) so it
 * can be unit-tested by direct invocation, and so the AMQP adapter stays a thin shell — the
 * {@code MovieProjector} idiom. A throw here means no ack and a redelivery, safe precisely
 * because every path below is idempotent: the confirm is one conditional UPDATE (re-running it
 * updates zero rows and re-reads CONFIRMED), and the failure mirror only lands on PENDING.
 *
 * <p><b>Nothing is ever decided from {@code paymentStatus}.</b> It is a read-model mirror for
 * display ("last attempt failed, you may retry"); the seat guard looks at {@code status} only.
 *
 * <p><b>The dual write is gone (BUILD_PLAN 4.1).</b> The confirm no longer raises an in-JVM event
 * for an AFTER_COMMIT publisher; it writes an {@code outbox} row <em>in the same transaction</em>
 * as the status change, so the two commit or roll back together. The outbox relay publishes those
 * rows afterwards — a broker outage now defers, it never drops, and a lost
 * {@code BookingConfirmationRejected} (a stranded refund) cannot happen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingConfirmer {

    private final BookingRepository bookings;
    private final OutboxMessageRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Tracer tracer;
    // The three collaborators the TICKET SNAPSHOT needs. They are read inside the confirm
    // transaction so the event carries what was true at that moment — see buildConfirmed().
    private final ShowtimeRepository showtimes;
    private final SeatRepository seats;
    private final MovieReadModel movies;

    /** What one {@code PaymentSucceeded} decided — returned so tests can assert the branch. */
    public enum ConfirmOutcome {
        /** The conditional update matched: PENDING with a live hold → CONFIRMED. */
        CONFIRMED,
        /** Zero rows, re-read CONFIRMED: a redelivered event. Idempotent no-op. */
        ALREADY_CONFIRMED,
        /** Zero rows, re-read terminal-or-lapsed: a rejection was published for auto-refund. */
        REJECTED,
        /** Zero rows, no such booking: logged, nothing published (should not happen — Payment
         * creates obligations off a booking read, and bookings are never deleted). */
        UNKNOWN_BOOKING
    }

    /**
     * Confirm a booking off a {@code PaymentSucceeded} event.
     *
     * <p>One conditional UPDATE decides the race with the expiry sweeper at the database: id
     * match + still PENDING + hold still live. One row → CONFIRMED + a {@link BookingConfirmed}
     * outbox row (same transaction — the outbox relay publishes it to the broker). Zero rows →
     * re-read and branch: CONFIRMED is a redelivery (no-op); EXPIRED/CANCELLED writes a
     * {@link BookingConfirmationRejected} carrying the event's {@code paymentId} so Payment can
     * refund without a lookup — the compensation trigger step 3.5 consumes.
     */
    @Transactional
    public ConfirmOutcome confirmFromPayment(PaymentSucceededEvent event) {
        Instant now = clock.instant();
        int rows = bookings.confirmIfStillPending(
                event.bookingId(), now, BookingStatus.PENDING, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        if (rows == 1) {
            // clearAutomatically flushed the persistence context, so this re-read is fresh.
            Booking booking = bookings.findById(event.bookingId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Booking " + event.bookingId() + " confirmed then vanished mid-transaction"));
            // eventId is a 0L placeholder; the relay stamps the outbox row id onto the wire so a
            // republished event keeps the same id and the consumer's dedupe fires.
            writeOutbox(buildConfirmed(booking, now),
                    OutboxEventType.BOOKING_CONFIRMED, booking.getId(),
                    RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY);
            log.info("Confirmed booking id={} ref={} off paymentId={} (eventId={})",
                    booking.getId(), booking.getBookingReference(), event.paymentId(), event.eventId());
            return ConfirmOutcome.CONFIRMED;
        }
        return bookings.findById(event.bookingId())
                .map(booking -> reject(booking, event, now))
                .orElseGet(() -> {
                    log.warn("PaymentSucceeded for unknown bookingId={} paymentId={} eventId={} — ignored",
                            event.bookingId(), event.paymentId(), event.eventId());
                    return ConfirmOutcome.UNKNOWN_BOOKING;
                });
    }

    /**
     * Mirror a {@code PaymentFailed} onto the read-model field — display only.
     *
     * <p>Conditional on still-PENDING so a late failure can never overwrite a PAID, and seats stay
     * held: the user retries until the hold lapses. Returns whether the mirror landed (tests assert
     * the out-of-order drop through this).
     */
    @Transactional
    public boolean mirrorFailure(PaymentFailedEvent event) {
        int rows = bookings.mirrorPaymentFailure(
                event.bookingId(), BookingStatus.PENDING, PaymentStatus.FAILED);
        if (rows == 1) {
            log.info("Mirrored PaymentFailed onto bookingId={} (attemptId={} reason={}) — seats stay held",
                    event.bookingId(), event.attemptId(), event.failureReason());
            return true;
        }
        log.debug("Dropped PaymentFailed for bookingId={} — no longer PENDING (eventId={})",
                event.bookingId(), event.eventId());
        return false;
    }

    /**
     * Assemble the {@link BookingConfirmed} event, snapshotting everything a ticket must print.
     *
     * <p><b>Why all of this is gathered HERE, inside the confirm transaction.</b> This is the one
     * moment where the booking, its seats, its showtime, that showtime's screen and theater, and the
     * cached movie are all reachable in one place with one consistent view. Notification cannot reach
     * any of it — it has no access to Booking's database and must not acquire one — so either these
     * facts travel on the event, or Notification calls back for them at send time and renders whatever
     * is true THEN rather than what was true now. For a ticket, the latter is wrong: see the class
     * javadoc on {@link BookingConfirmed}.
     *
     * <p><b>Nothing here may fail the confirm.</b> The booking is already updated and the money has
     * already moved; a missing showtime row or an unresolvable movie title must degrade to a ticket
     * with a blank field, never to a rolled-back sale. Every lookup below is therefore null-tolerant,
     * and the email template is built to render without any of them.
     */
    private BookingConfirmed buildConfirmed(Booking booking, Instant now) {
        // One query, both associations fetched — the join the ticket needs, and the one Notification
        // would otherwise have had to make over HTTP. open-in-view is false, so this explicit fetch is
        // what makes screen/theater readable at all.
        Showtime showtime = showtimes.findWithScreenAndTheaterById(booking.getShowtimeId()).orElse(null);
        MovieProjectionData movie = movies.movieById(booking.getMovieId()).orElse(null);

        return new BookingConfirmed(0L,
                booking.getId(),
                booking.getBookingReference(),
                booking.getUserId(),
                booking.getMovieId(),
                movie == null ? null : movie.title(),
                movie == null ? null : movie.posterPath(),
                startsAt(showtime),
                showtime == null ? null : showtime.getScreen().getTheater().getName(),
                showtime == null ? null : showtime.getScreen().getName(),
                ticketSeats(booking),
                booking.getTotalAmount(),
                booking.getCurrency(),
                now);
    }

    /**
     * The showtime's start as a UTC instant.
     *
     * <p>{@code Showtime} stores a local date and a local time — correct for the theater, which thinks
     * in wall-clock terms. An instant is what crosses the wire: the consumer formats it for display,
     * and unlike a bare {@code LocalDateTime} it cannot be silently reinterpreted in a different zone.
     * The zone comes from the injected {@link Clock}, so a test with a fixed-zone clock gets a
     * deterministic value instead of depending on the host's timezone.
     */
    private Instant startsAt(Showtime showtime) {
        if (showtime == null) {
            return null;
        }
        return showtime.getShowDate().atTime(showtime.getShowTime()).atZone(clock.getZone()).toInstant();
    }

    /**
     * Turn the booking's line items into printable ticket seats.
     *
     * <p>{@code booking_seats} holds a {@code seatId}, which means nothing to a customer — the ticket
     * needs "E5". The labels come from one batched {@code IN} query rather than a lookup per seat: a
     * party of six would otherwise be six round trips to the database on the confirm path.
     *
     * <p>Price and type name are read straight off the booking's own line items, NOT recomputed from
     * the seat's current type — they were frozen at booking time precisely so a later price change
     * cannot rewrite what someone already paid.
     */
    private List<TicketSeat> ticketSeats(Booking booking) {
        List<Long> seatIds = booking.getSeats().stream().map(BookingSeat::getSeatId).toList();
        if (seatIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Seat> byId = seats.findWithSeatTypeByIdIn(seatIds).stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity(), (a, b) -> a));
        return booking.getSeats().stream()
                .map(line -> new TicketSeat(
                        label(byId.get(line.getSeatId())),
                        line.getSeatTypeName(),
                        line.getSeatPrice()))
                .toList();
    }

    /** {@code E} + {@code 5} → {@code "E5"}; null when the seat row vanished (never expected). */
    private String label(Seat seat) {
        return seat == null ? null : seat.getSeatRow() + seat.getSeatNumber();
    }

    private ConfirmOutcome reject(Booking booking, PaymentSucceededEvent event, Instant now) {
        return switch (booking.getStatus()) {
            case CONFIRMED -> {
                log.debug("Redelivered PaymentSucceeded for already-CONFIRMED booking id={} — no-op (eventId={})",
                        booking.getId(), event.eventId());
                yield ConfirmOutcome.ALREADY_CONFIRMED;
            }
            case EXPIRED, CANCELLED -> {
                writeRejection(booking, event,
                        ConfirmationRejectionReason.valueOf(booking.getStatus().name()), now);
                yield ConfirmOutcome.REJECTED;
            }
            case PENDING -> rejectLapsedHold(booking, event, now);
        };
    }

    /**
     * The update matched zero rows yet the booking still reads PENDING — so the hold must have
     * lapsed between... more precisely, the {@code expiresAt} guard failed at update time while
     * the status was PENDING (a concurrent confirm could not have won: it needs a live hold too,
     * and a concurrent sweeper flip would read back EXPIRED, not PENDING).
     *
     * <p>Rather than trusting the sweeper to arrive within the minute, expire the hold inline with
     * the same guarded update and write the rejection now: a paid-but-undecided event that merely
     * went back on the queue would strand the customer's refund if the sweeper were down. Either
     * order ends the same — EXPIRED plus an auto-refund — because the sweeper's update is guarded
     * identically and emits nothing itself.
     */
    private ConfirmOutcome rejectLapsedHold(Booking booking, PaymentSucceededEvent event, Instant now) {
        bookings.expireIfStillPending(booking.getId(), BookingStatus.PENDING, BookingStatus.EXPIRED);
        writeRejection(booking, event, ConfirmationRejectionReason.EXPIRED, now);
        return ConfirmOutcome.REJECTED;
    }

    private void writeRejection(Booking booking, PaymentSucceededEvent event,
            ConfirmationRejectionReason reason, Instant now) {
        writeOutbox(new BookingConfirmationRejected(0L,
                        booking.getId(), booking.getBookingReference(), event.paymentId(),
                        reason, booking.getUserId(), now),
                OutboxEventType.BOOKING_CONFIRMATION_REJECTED, booking.getId(),
                RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);
        log.info("PaymentSucceeded arrived too late for booking id={} ref={} (status={}) — "
                        + "rejection written for auto-refund of paymentId={} (eventId={})",
                booking.getId(), booking.getBookingReference(), booking.getStatus(),
                event.paymentId(), event.eventId());
    }

    /**
     * The outbox write — the "announcement" half of the confirm/reject. Runs inside the caller's
     * transaction, so the booking row and this row commit or roll back together (BUILD_PLAN 4.1).
     * The event is serialized once here with a placeholder {@code eventId}; the relay injects the
     * outbox row id onto the wire so redeliveries carry a stable dedupe key.
     *
     * <p>The trace context is captured HERE, on this thread, inside the transaction — the one moment
     * the original trace is still live. The relay publishes seconds later on
     * a scheduler thread where no trace exists, so if the context were not persisted it would be
     * lost forever; persisting it is the same principle the outbox itself runs on. This thread is
     * the PaymentEventListener's consumer thread, whose trace the listener restores from the
     * {@code traceparent} header the payment relay stamped — so the whole confirm→email chain stays
     * in the payment webhook's trace.
     */
    private void writeOutbox(Object event, OutboxEventType type, Long aggregateId, String routingKey) {
        try {
            Span current = tracer.currentSpan();
            outbox.save(new OutboxMessage(type, aggregateId, routingKey,
                    objectMapper.writeValueAsString(event),
                    current == null ? null : current.context().traceId(),
                    current == null ? null : current.context().spanId()));
        } catch (JsonProcessingException e) {
            // Serializing a record we control should never fail; if it does, fail the whole
            // transaction loudly rather than commit a booking with no announcement.
            throw new BookingException(BookingErrorCode.BOOKING_INTERNAL_ERROR,
                    "Failed to serialize " + type + " event for booking " + aggregateId, e);
        }
    }
}