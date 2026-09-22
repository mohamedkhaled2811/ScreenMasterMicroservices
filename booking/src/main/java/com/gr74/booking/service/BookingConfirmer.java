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
 * Applies payment outcomes to bookings. All paths are idempotent; the outbox row
 * is written in the same transaction as the status change.
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
    // Read inside the confirm transaction so the event snapshots values as of confirm time.
    private final ShowtimeRepository showtimes;
    private final SeatRepository seats;
    private final MovieReadModel movies;

    /** Result of handling one {@code PaymentSucceeded} event. */
    public enum ConfirmOutcome {
        /** Conditional update matched: PENDING with a live hold became CONFIRMED. */
        CONFIRMED,
        /** Zero rows, re-read CONFIRMED: redelivered event, no-op. */
        ALREADY_CONFIRMED,
        /** Zero rows, re-read terminal-or-lapsed: rejection published for auto-refund. */
        REJECTED,
        /** Zero rows, no such booking: logged and ignored. */
        UNKNOWN_BOOKING
    }

    /**
     * Confirm a booking from a {@code PaymentSucceeded} event.
     * One conditional UPDATE decides the race with the expiry sweeper at the database.
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
            // eventId placeholder; the relay stamps the outbox row id onto the wire event.
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
     * Mirror a {@code PaymentFailed} onto the read-model field. Conditional on still-PENDING
     * so a late failure never overwrites PAID; seats stay held for retry.
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
     * Assemble the {@link BookingConfirmed} event, snapshotting ticket fields.
     * Every lookup is null-tolerant so a missing row degrades to a blank field, never a rollback.
     */
    private BookingConfirmed buildConfirmed(Booking booking, Instant now) {
        // One query with both associations fetched; open-in-view is false so explicit fetch is required.
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

    /** The showtime's start as a UTC instant, using the injected clock's zone. */
    private Instant startsAt(Showtime showtime) {
        if (showtime == null) {
            return null;
        }
        return showtime.getShowDate().atTime(showtime.getShowTime()).atZone(clock.getZone()).toInstant();
    }

    /**
     * Turn line items into printable ticket seats. Labels come from one batched query;
     * price and type name are read off the frozen line items, not recomputed.
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

    /** {@code E} + {@code 5} → {@code "E5"}; null when the seat row is missing. */
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
     * The update matched zero rows yet the booking still reads PENDING, so the hold lapsed.
     * Expire inline with the same guarded update and write the rejection now.
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
     * Write the outbox row inside the caller's transaction. Trace context is captured here
     * since the relay publishes later on a scheduler thread with no live trace.
     */
    private void writeOutbox(Object event, OutboxEventType type, Long aggregateId, String routingKey) {
        try {
            Span current = tracer.currentSpan();
            outbox.save(new OutboxMessage(type, aggregateId, routingKey,
                    objectMapper.writeValueAsString(event),
                    current == null ? null : current.context().traceId(),
                    current == null ? null : current.context().spanId()));
        } catch (JsonProcessingException e) {
            // Serializing a record we control should never fail; fail loudly instead of
            // committing a booking with no announcement.
            throw new BookingException(BookingErrorCode.BOOKING_INTERNAL_ERROR,
                    "Failed to serialize " + type + " event for booking " + aggregateId, e);
        }
    }
}