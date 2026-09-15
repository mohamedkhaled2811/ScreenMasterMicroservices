package com.gr74.notification.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.notification.config.RabbitConfig;
import com.gr74.notification.service.NotificationService;

/**
 * The thin AMQP shell's routing: which routing key is dispatched to which service method, with the
 * payload converted to the right record — and that an unexpected key is ignored, not thrown at.
 *
 * <p>The listener never touches a broker here; it is constructed directly with mocked collaborators
 * (the {@code PaymentEventListenerTest} shape in miniature). What is NOT covered is the
 * queue/binding/converter wiring itself — that seam is exercised once, live, in the demo.
 */
class BookingEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ObjectMapper objectMapper;

    private BookingEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new BookingEventListener(notificationService, objectMapper);
    }

    @Test
    @DisplayName("booking-confirmed-key converts to BookingConfirmedEvent and delegates")
    void dispatchesConfirmedEvent() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(1L, 101L, "BK-00001", "user-1",
                Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY);

        verify(notificationService).processBookingConfirmed(event);
        verify(notificationService, never()).processBookingConfirmationRejected(any());
    }

    @Test
    @DisplayName("booking-confirmation-rejected-key converts to the rejection record and delegates")
    void dispatchesRejectedEvent() {
        BookingConfirmationRejectedEvent event = new BookingConfirmationRejectedEvent(2L, 202L,
                "BK-00002", 900L, ConfirmationRejectionReason.CANCELLED, "user-1",
                Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmationRejectedEvent.class)))
                .thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);

        verify(notificationService).processBookingConfirmationRejected(event);
        verify(notificationService, never()).processBookingConfirmed(any());
    }

    @Test
    @DisplayName("an unexpected routing key is ignored, not dispatched and not thrown")
    void ignoresUnexpectedRoutingKey() {
        listener.onBookingEvent(Map.of(), "some-unknown-key");

        verify(notificationService, never()).processBookingConfirmed(any());
        verify(notificationService, never()).processBookingConfirmationRejected(any());
    }
}