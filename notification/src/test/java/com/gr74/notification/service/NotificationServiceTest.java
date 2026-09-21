package com.gr74.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.notification.channel.Notification;
import com.gr74.notification.channel.NotificationChannel;
import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.exception.NotificationSendException;
import com.gr74.notification.identity.KeycloakUserClient;
import com.gr74.notification.messaging.BookingConfirmationRejectedEvent;
import com.gr74.notification.messaging.BookingConfirmedEvent;
import com.gr74.notification.messaging.ConfirmationRejectionReason;
import com.gr74.notification.messaging.TicketSeat;
import com.gr74.notification.model.ProcessedEvent;
import com.gr74.notification.repository.ProcessedEventRepository;

/**
 * The idempotent consumer's contract, tested by <b>direct invocation</b> of
 * {@link NotificationService} on H2 — no broker and no mail server (the {@code BookingConfirmerTest}
 * idiom). What it proves:
 * <ol>
 *   <li>a first delivery claims the event AND sends, with the ticket facts on the message;</li>
 *   <li>a redelivery of the SAME eventId sends <b>zero</b> further emails — the property that used
 *       to be about a log line and is now about a real, non-idempotent side effect;</li>
 *   <li>a failing channel propagates, so the claim rolls back and the broker can redeliver;</li>
 *   <li>both event types render their own template.</li>
 * </ol>
 *
 * <p><b>Why a recording stub rather than a Mockito mock for the channel.</b> The central assertion
 * is "exactly one send across two deliveries", and a list of what was actually sent states that
 * directly — including <em>what</em> was in the message, which a {@code verify(times(1))} does not
 * show when it fails. The Keycloak lookup IS a mock: it is a network call with no behaviour worth
 * reproducing here.
 *
 * <p>The concurrent-claim path (a {@code DataIntegrityViolationException} before any row exists
 * locally) is covered separately by {@link NotificationServiceConcurrentClaimTest}.
 */
@DataJpaTest
@Import({NotificationService.class, NotificationServiceTest.StubChannelConfig.class})
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final Instant SHOWTIME = Instant.parse("2026-09-25T19:30:00Z");
    private static final String EMAIL = "alice@screenmaster.local";

    /**
     * The stub channel, plus real {@link NotificationProps} — the props are a plain record with no
     * behaviour to fake, and using the real one means the poster-URL composition is exercised here
     * instead of being mocked away.
     */
    @TestConfiguration
    static class StubChannelConfig {

        @Bean
        RecordingChannel recordingChannel() {
            return new RecordingChannel();
        }

        @Bean
        NotificationProps notificationProps() {
            return new NotificationProps(
                    new NotificationProps.Mail("tickets@screenmaster.local", "ScreenMaster"),
                    new NotificationProps.Image("https://image.tmdb.org/t/p", "w780"),
                    new NotificationProps.Identity("http://keycloak:8180", "cinema",
                            java.time.Duration.ofMinutes(10), 10_000));
        }
    }

    /** Records what was sent instead of delivering it. Throws on demand to test the failure path. */
    static class RecordingChannel implements NotificationChannel {

        final List<Notification> sent = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void send(Notification notification) {
            if (failWith != null) {
                throw failWith;
            }
            sent.add(notification);
        }

        /** The context (and therefore this bean) is cached across test methods — reset per test. */
        void reset() {
            sent.clear();
            failWith = null;
        }
    }

    @Autowired
    private NotificationService service;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private RecordingChannel channel;

    @MockitoBean
    private KeycloakUserClient users;

    @BeforeEach
    void resetChannel() {
        channel.reset();
    }

    @Test
    @DisplayName("first delivery claims the eventId and sends the ticket")
    void firstDeliverySendsAndClaims() {
        given(users.emailOf("user-1")).willReturn(EMAIL);
        BookingConfirmedEvent event = confirmed(1L);

        boolean sent = service.processBookingConfirmed(event);

        assertThat(sent).isTrue();
        assertThat(channel.sent).hasSize(1);
        Notification message = channel.sent.getFirst();
        assertThat(message.recipient()).isEqualTo(EMAIL);
        assertThat(message.templateName()).isEqualTo("booking-confirmed");
        assertThat(message.subject()).contains("The Matrix");

        ProcessedEvent claim = processedEvents.findById(event.eventId()).orElseThrow();
        assertThat(claim.getEventType()).isEqualTo(NotificationService.BOOKING_CONFIRMED);
        assertThat(claim.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("the ticket carries every fact the template renders, poster path composed into a URL")
    void ticketVariablesAreComplete() {
        given(users.emailOf("user-1")).willReturn(EMAIL);

        service.processBookingConfirmed(confirmed(5L));

        // These keys are the contract between the service and booking-confirmed.html. A rename on
        // either side that isn't mirrored shows up here, not as a silently blank field in a customer's
        // inbox — which is the actual failure mode of template variables.
        assertThat(channel.sent.getFirst().variables())
                .containsEntry("bookingReference", "BK-00005")
                .containsEntry("movieTitle", "The Matrix")
                .containsEntry("theaterName", "Downtown Cinema")
                .containsEntry("screenName", "Screen 1")
                .containsEntry("totalAmount", new BigDecimal("36.00"))
                .containsEntry("currency", "EGP")
                // The event carries a PATH; the email needs a URL. Base + size + path, composed here.
                .containsEntry("posterUrl", "https://image.tmdb.org/t/p/w780/matrix.jpg")
                .containsKey("showtime")
                .hasEntrySatisfying("seats",
                        seats -> assertThat((List<?>) seats).hasSize(2));
    }

    @Test
    @DisplayName("redelivery of the same eventId does NOT send a second email")
    void redeliveryOfSameEventIdDoesNotSendAgain() {
        given(users.emailOf("user-1")).willReturn(EMAIL);
        BookingConfirmedEvent event = confirmed(2L);

        assertThat(service.processBookingConfirmed(event)).isTrue();
        assertThat(processedEvents.count()).isEqualTo(1); // the first claim landed

        // The duplicate claim marks the (test) transaction rollback-only, so no DB query after this
        // point — the no-op is asserted on the return value and on what the channel received.
        boolean redelivery = service.processBookingConfirmed(event);

        assertThat(redelivery).isFalse();
        // THE POINT OF THE WHOLE PHASE: two deliveries, one email. Sending is not idempotent, so the
        // claim is the only thing standing between a redelivery and a customer's duplicate ticket.
        assertThat(channel.sent).hasSize(1);
    }

    @Test
    @DisplayName("a failing channel propagates so the claim rolls back and the broker redelivers")
    void sendFailurePropagates() {
        given(users.emailOf("user-1")).willReturn(EMAIL);
        channel.failWith = new NotificationSendException("SMTP refused", new RuntimeException("boom"));

        // Must THROW, not swallow. A caught failure here would commit the claim for an email that was
        // never sent — marking a lost ticket as processed, invisibly.
        assertThatThrownBy(() -> service.processBookingConfirmed(confirmed(6L)))
                .isInstanceOf(NotificationSendException.class);
        assertThat(channel.sent).isEmpty();
    }

    @Test
    @DisplayName("an unresolvable recipient propagates before anything is sent")
    void recipientLookupFailurePropagates() {
        willThrow(new IllegalStateException("keycloak down")).given(users).emailOf("user-1");

        assertThatThrownBy(() -> service.processBookingConfirmed(confirmed(7L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(channel.sent).isEmpty();
    }

    @Test
    @DisplayName("each event type renders its own template")
    void bothEventTypesRenderTheirOwnTemplate() {
        given(users.emailOf("user-1")).willReturn(EMAIL);

        assertThat(service.processBookingConfirmed(confirmed(3L))).isTrue();
        assertThat(service.processBookingConfirmationRejected(rejected(4L))).isTrue();

        assertThat(channel.sent).hasSize(2);
        assertThat(channel.sent.get(0).templateName()).isEqualTo("booking-confirmed");

        Notification refund = channel.sent.get(1);
        assertThat(refund.templateName()).isEqualTo("booking-rejected");
        assertThat(refund.subject()).contains("BK-00004");
        assertThat(refund.variables())
                .containsEntry("bookingReference", "BK-00004")
                .containsEntry("paymentId", 900L)
                .containsEntry("reason", "EXPIRED");

        assertThat(processedEvents.findById(3L)).isPresent();
        assertThat(processedEvents.findById(4L).orElseThrow().getEventType())
                .isEqualTo(NotificationService.BOOKING_CONFIRMATION_REJECTED);
    }

    @Test
    @DisplayName("a movie with no poster still sends — the ticket drops the image, not the email")
    void missingPosterStillSends() {
        given(users.emailOf("user-1")).willReturn(EMAIL);

        // posterPath null: TMDB has no artwork for some titles. Decoration must never veto delivery.
        service.processBookingConfirmed(new BookingConfirmedEvent(8L, 108L, "BK-00008", "user-1",
                603L, "The Matrix", null, SHOWTIME, "Downtown Cinema", "Screen 1",
                List.of(new TicketSeat("E5", "STANDARD", new BigDecimal("18.00"))),
                new BigDecimal("18.00"), "EGP", NOW));

        assertThat(channel.sent).hasSize(1);
        assertThat(channel.sent.getFirst().variables()).containsEntry("posterUrl", null);
    }

    private BookingConfirmedEvent confirmed(long eventId) {
        return new BookingConfirmedEvent(eventId, 100L + eventId, "BK-%05d".formatted(eventId),
                "user-1", 603L, "The Matrix", "/matrix.jpg", SHOWTIME,
                "Downtown Cinema", "Screen 1",
                List.of(new TicketSeat("E5", "STANDARD", new BigDecimal("18.00")),
                        new TicketSeat("E6", "STANDARD", new BigDecimal("18.00"))),
                new BigDecimal("36.00"), "EGP", NOW);
    }

    private BookingConfirmationRejectedEvent rejected(long eventId) {
        return new BookingConfirmationRejectedEvent(eventId, 200L + eventId,
                "BK-%05d".formatted(eventId), 900L, ConfirmationRejectionReason.EXPIRED, "user-1", NOW);
    }
}
