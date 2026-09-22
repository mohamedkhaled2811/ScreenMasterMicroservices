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
 * The idempotent consumer (BUILD_PLAN 4.3): claims an event by inserting its id, and only then sends
 * the email — a real HTML ticket over SMTP, rendered from the facts Booking snapshotted onto the
 * event and addressed to the email resolved from Keycloak.
 *
 * <p><b>Why the claim-first ordering is the whole point.</b> RabbitMQ delivers at-least-once: a
 * consumer crash before the ack, a broker restart, or the {@code --scale notification=2} demo can all
 * deliver the same event twice. So the send must be a no-op on the second delivery. The guard is the
 * {@code processed_events} INSERT, not a check: we insert the row first and treat a
 * {@link DataIntegrityViolationException} AS the dedupe (a duplicate claim → ack-and-no-op, the
 * {@code WebhookWriter#storeVerifiedEvent} idiom). There is deliberately no {@code existsById(...)}
 * before the insert — that read-then-insert has a window where two concurrent consumer instances both
 * pass the check and both send, which is exactly the double-send this phase exists to prove
 * impossible.
 *
 * <p><b>The honest residual.</b> This is at-least-once with an idempotent consumer, i.e. exactly-once
 * <em>in effect</em> — the only exactly-once that exists across a broker. One gap remains, and it is
 * real now that the side effect leaves the process: a crash after SMTP accepted the message but
 * before the transaction commits rolls back the claim, and the redelivery sends a second email. It
 * cannot be closed without a distributed transaction. Against a provider that supports it you would
 * pass {@code eventId} as the provider's own idempotency key (the SES/SendGrid message id) and let
 * the provider collapse the duplicate; local SMTP has no such key, so this stays documented rather
 * than claimed as solved.
 *
 * <p><b>This service decides WHAT to say; it never delivers.</b> Delivery is the
 * {@link NotificationChannel}'s job — which is what makes the dedupe testable with a stub channel
 * instead of a mail server, and what lets an SMS channel be added without touching this class.
 *
 * <p>A plain {@code @Transactional} service — deliberately NOT the {@code @RabbitListener} itself —
 * so it is unit-testable by direct invocation without a broker (the {@code BookingConfirmer} /
 * {@code MovieProjector} idiom). The listener is a thin shell over it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** The {@code event_type} written onto a {@code BookingConfirmed} dedupe row. */
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";

    /** The {@code event_type} written onto a {@code BookingConfirmationRejected} dedupe row. */
    public static final String BOOKING_CONFIRMATION_REJECTED = "BOOKING_CONFIRMATION_REJECTED";

    /**
     * How a screening time is printed on the ticket, e.g. {@code Fri 25 Sep 2026, 19:30}. A pinned
     * pattern and locale, so the output cannot drift with the container's default locale.
     */
    private static final DateTimeFormatter SHOWTIME_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.ENGLISH);

    private final ProcessedEventRepository processedEvents;
    private final NotificationChannel channel;
    private final KeycloakUserClient users;
    private final NotificationProps props;
    /** The zone showtimes are displayed in — the JVM default, so a container's TZ env var sets it. */
    private final ZoneId zone = ZoneId.systemDefault();

    /**
     * Send the ticket for a confirmed booking.
     *
     * <p>Ordering is load-bearing: <b>claim, then send</b>. The claim's INSERT is what makes a
     * redelivery a no-op, and it must happen before the side effect — checking "did I already send?"
     * after sending would be a race two consumer instances both lose.
     *
     * <p>The Keycloak lookup happens AFTER the claim on purpose. If it throws (the IdP is down), the
     * transaction rolls back, taking the claim with it, and the broker redelivers — so the retry
     * re-claims cleanly and tries again. Looking the address up before the claim would mean an
     * outage burned the claim without sending anything.
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

    /**
     * The subject line. Names the movie when we have it, because a subject reading "Your ScreenMaster
     * tickets" for four different bookings is useless in a crowded inbox — and falls back to the
     * booking reference, which is always present, when the title could not be resolved.
     */
    private String subjectFor(BookingConfirmedEvent event) {
        return event.movieTitle() == null
                ? "Your tickets — booking " + event.bookingReference()
                : "Your tickets for " + event.movieTitle();
    }

    /**
     * The template's variable map.
     *
     * <p>Every value the template touches is put here explicitly — including the ones that may be
     * null. Thymeleaf renders a missing variable as empty rather than failing, which would turn a
     * renamed field into a silently blank ticket; naming them all keeps the contract between this
     * method and the template visible in one place.
     *
     * <p>Note what this method does: it turns wire data into <em>display</em> data (a formatted date,
     * a composed poster URL). That belongs here rather than in the template, because a second channel
     * would want the same formatted values from the same map.
     */
    private Map<String, Object> confirmedVariables(BookingConfirmedEvent event) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("bookingReference", event.bookingReference());
        vars.put("movieTitle", event.movieTitle());
        // Path -> full CDN URL. Null when there is no artwork; the template drops the image band.
        vars.put("posterUrl", props.image().posterUrl(event.posterPath()));
        vars.put("showtime", formatShowtime(event.showtimeStartsAt()));
        vars.put("theaterName", event.theaterName());
        vars.put("screenName", event.screenName());
        vars.put("seats", event.seats() == null ? List.of() : event.seats());
        vars.put("totalAmount", event.totalAmount());
        vars.put("currency", event.currency());
        return vars;
    }

    /**
     * Format the screening time for a human, e.g. {@code Fri 25 Sep 2026, 19:30}.
     *
     * <p>Rendered in the service's configured zone: the instant on the wire is unambiguous, but a
     * customer wants the wall-clock time they should arrive at, not UTC. {@code Locale.ENGLISH} is
     * pinned so the output does not silently change with the container's locale — a formatted date
     * that varies by deployment environment is a bug that only shows up in production.
     */
    private String formatShowtime(Instant startsAt) {
        return startsAt == null ? null : SHOWTIME_FORMAT.format(startsAt.atZone(zone));
    }

    /**
     * Tell the customer their payment arrived too late and the money is coming back.
     *
     * <p>Same claim-then-send ordering as the confirmation, and the same reason for it. This message
     * matters more than the ticket, not less: it is the only thing standing between a customer and
     * silently taken money, which is why {@code BookingConfirmationRejected} travels through the
     * transactional outbox rather than a best-effort publish.
     *
     * <p>No poster and no seats here — the booking never happened. The email's job is to state the
     * refund plainly, so it is deliberately a plain notice rather than a decorated ticket.
     */
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
     * THE CLAIM: try to insert the dedupe row. {@code true} means this consumer won the event and may
     * send; {@code false} means a duplicate/redelivery — someone already processed it, so the caller
     * returns without sending.
     *
     * <p>The repository's {@code claim} is a raw INSERT (see its javadoc for why not
     * {@code saveAndFlush}): it either creates the row or violates the PK. On the duplicate the
     * failed statement leaves the transaction in a state we do not want to commit, so we mark it
     * rollback-only explicitly rather than letting Spring commit over a failed insert. Rolling back
     * cleanly is what lets the listener ack the redelivery as handled.
     */
    private boolean claim(long eventId, String eventType) {
        try {
            processedEvents.claim(eventId, eventType, Instant.now());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // The dedupe fired: a concurrent (or redelivered) claim won. Ack-and-no-op — do NOT rethrow.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Duplicate booking event eventId={} eventType={} — already processed, not sending again",
                    eventId, eventType);
            return false;
        }
    }
}