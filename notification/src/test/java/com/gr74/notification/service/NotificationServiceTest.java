package com.gr74.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

import com.gr74.notification.messaging.BookingConfirmationRejectedEvent;
import com.gr74.notification.messaging.BookingConfirmedEvent;
import com.gr74.notification.messaging.ConfirmationRejectionReason;
import com.gr74.notification.model.ProcessedEvent;
import com.gr74.notification.repository.ProcessedEventRepository;

/**
 * The idempotent consumer's contract, tested by <b>direct invocation</b> of
 * {@link NotificationService} on H2 — no broker (the {@code BookingConfirmerTest} idiom). What it
 * proves:
 * <ol>
 *   <li>a first delivery claims the event AND sends;</li>
 *   <li>a redelivery of the SAME eventId is a no-op — the duplicate PK insert fails, the send is
 *       suppressed, and the dedupe row is not duplicated;</li>
 *   <li>both event types produce their own distinct message.</li>
 * </ol>
 *
 * <p>The send is a log line, so "sent" is asserted on captured output. The concurrent-claim path
 * (a {@code DataIntegrityViolationException} from the repository before any row exists locally) is
 * covered separately by {@link NotificationServiceConcurrentClaimTest}.
 */
@DataJpaTest
@Import(NotificationService.class)
@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    @Autowired
    private NotificationService service;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Test
    @DisplayName("first delivery claims the eventId and sends")
    void firstDeliverySendsAndClaims(CapturedOutput output) {
        BookingConfirmedEvent event = confirmed(1L);

        boolean sent = service.processBookingConfirmed(event);

        assertThat(sent).isTrue();
        assertThat(output).contains("booking confirmed, ticket emailed")
                .contains("bookingReference=BK-00001")
                .contains("userId=user-1");
        ProcessedEvent claim = processedEvents.findById(event.eventId()).orElseThrow();
        assertThat(claim.getEventType()).isEqualTo(NotificationService.BOOKING_CONFIRMED);
        assertThat(claim.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("redelivery of the same eventId does NOT send a second time")
    void redeliveryOfSameEventIdDoesNotSendAgain(CapturedOutput output) {
        BookingConfirmedEvent event = confirmed(2L);

        assertThat(service.processBookingConfirmed(event)).isTrue();
        assertThat(processedEvents.count()).isEqualTo(1); // the first claim landed

        // The duplicate claim marks the (test) transaction rollback-only, so no DB query after this
        // point — the no-op is asserted on the return value and the captured output instead.
        boolean redelivery = service.processBookingConfirmed(event);

        assertThat(redelivery).isFalse();
        assertThat(output).containsOnlyOnce("booking confirmed, ticket emailed");
        assertThat(output).contains("already processed, not sending again");
    }

    @Test
    @DisplayName("each event type produces its own distinct message")
    void bothEventTypesProduceDistinctMessages(CapturedOutput output) {
        assertThat(service.processBookingConfirmed(confirmed(3L))).isTrue();
        assertThat(service.processBookingConfirmationRejected(rejected(4L))).isTrue();

        assertThat(output).contains("booking confirmed, ticket emailed");
        assertThat(output).contains("payment arrived too late, you have been refunded")
                .contains("bookingReference=BK-00004")
                .contains("paymentId=900")
                .contains("reason=EXPIRED");
        assertThat(processedEvents.findById(3L)).isPresent();
        assertThat(processedEvents.findById(4L).orElseThrow().getEventType())
                .isEqualTo(NotificationService.BOOKING_CONFIRMATION_REJECTED);
    }

    private BookingConfirmedEvent confirmed(long eventId) {
        return new BookingConfirmedEvent(eventId, 100L + eventId, "BK-%05d".formatted(eventId),
                "user-1", NOW);
    }

    private BookingConfirmationRejectedEvent rejected(long eventId) {
        return new BookingConfirmationRejectedEvent(eventId, 200L + eventId,
                "BK-%05d".formatted(eventId), 900L, ConfirmationRejectionReason.EXPIRED, "user-1", NOW);
    }
}