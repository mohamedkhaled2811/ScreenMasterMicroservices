package com.gr74.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.gr74.notification.channel.Notification;
import com.gr74.notification.channel.NotificationChannel;
import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.identity.KeycloakUserClient;
import com.gr74.notification.messaging.BookingConfirmationRejectedEvent;
import com.gr74.notification.messaging.BookingConfirmedEvent;
import com.gr74.notification.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Idempotent consumer for booking outcomes: claims the event id first, then sends the email.
 * A duplicate claim is a no-op, so redeliveries never send twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Event type written onto a {@code BookingConfirmed} dedupe row. */
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";

    /** Event type written onto a {@code BookingConfirmationRejected} dedupe row. */
    public static final String BOOKING_CONFIRMATION_REJECTED = "BOOKING_CONFIRMATION_REJECTED";

    /** Showtime display format, e.g. {@code Fri 25 Sep 2026, 19:30}. */
    private static final DateTimeFormatter SHOWTIME_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.ENGLISH);

    private final ProcessedEventRepository processedEvents;
    private final NotificationChannel channel;
    private final KeycloakUserClient users;
    private final NotificationProps props;
    /** Zone showtimes are displayed in. */
    private final ZoneId zone = ZoneId.systemDefault();

    /**
     * Sends the ticket for a confirmed booking.
     * Claims before sending so redelivery is a no-op.
     *
     * @return {@code true} if this delivery sent the email; {@code false} if it was a duplicate
     */
    @Transactional
    public boolean processBookingConfirmed(BookingConfirmedEvent event) {
        if (!claim(event.eventId(), BOOKING_CONFIRMED)) {
            return false;
        }
        String recipient = users.emailOf(event.userId());
        channel.send(new Notification(recipient,
                subjectFor(event),
                "booking-confirmed",
                confirmedVariables(event)));
        log.info("Ticket emailed — bookingReference={} bookingId={} seats={}",
                event.bookingReference(), event.bookingId(),
                event.seats() == null ? 0 : event.seats().size());
        return true;
    }

    /** Builds the email subject, falling back to the booking reference when the title is missing. */
    private String subjectFor(BookingConfirmedEvent event) {
        return event.movieTitle() == null
                ? "Your tickets — booking " + event.bookingReference()
                : "Your tickets for " + event.movieTitle();
    }

    /** Template variables for the confirmation email. */
    private Map<String, Object> confirmedVariables(BookingConfirmedEvent event) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("bookingReference", event.bookingReference());
        vars.put("movieTitle", event.movieTitle());
        // Null when there is no artwork.
        vars.put("posterUrl", props.image().posterUrl(event.posterPath()));
        vars.put("showtime", formatShowtime(event.showtimeStartsAt()));
        vars.put("theaterName", event.theaterName());
        vars.put("screenName", event.screenName());
        vars.put("seats", event.seats() == null ? List.of() : event.seats());
        vars.put("totalAmount", event.totalAmount());
        vars.put("currency", event.currency());
        return vars;
    }

    /** Formats the showtime for display, or null when absent. */
    private String formatShowtime(Instant startsAt) {
        return startsAt == null ? null : SHOWTIME_FORMAT.format(startsAt.atZone(zone));
    }

    /** Sends the refund notice for a rejected confirmation; duplicates return false. */
    @Transactional
    public boolean processBookingConfirmationRejected(BookingConfirmationRejectedEvent event) {
        if (!claim(event.eventId(), BOOKING_CONFIRMATION_REJECTED)) {
            return false;
        }
        String recipient = users.emailOf(event.userId());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("bookingReference", event.bookingReference());
        vars.put("reason", event.reason() == null ? null : event.reason().name());
        vars.put("paymentId", event.paymentId());
        channel.send(new Notification(recipient,
                "Refund on its way — booking " + event.bookingReference(),
                "booking-rejected",
                vars));
        log.info("Refund notice emailed — bookingReference={} paymentId={} reason={}",
                event.bookingReference(), event.paymentId(), event.reason());
        return true;
    }

    /**
     * Inserts the dedupe row; false on duplicate.
     * Marks rollback-only on duplicate so the redelivery acks cleanly.
     */
    private boolean claim(long eventId, String eventType) {
        try {
            processedEvents.claim(eventId, eventType, Instant.now());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // Duplicate claim — ack as handled without sending.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Duplicate booking event eventId={} eventType={} — already processed, not sending again",
                    eventId, eventType);
            return false;
        }
    }
}