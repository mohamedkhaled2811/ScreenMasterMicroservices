package com.gr74.notification.messaging;

import java.util.Map;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.notification.config.RabbitConfig;
import com.gr74.notification.service.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The AMQP adapter for Booking's booking-outcome stream — a deliberately thin shell over
 * {@link NotificationService}. All the interesting behaviour (the claim-first dedupe) lives in the
 * service so it is unit-testable without a broker; this class only bridges the queue to it.
 *
 * <p>An exception thrown here (e.g. the DB is momentarily down) propagates, the message is
 * <em>not</em> acked, and RabbitMQ redelivers it — safe precisely because the service is idempotent
 * (a redelivery re-inserts the same eventId and the dedupe no-ops). No transaction, no try/catch:
 * the {@code PaymentEventListener} idiom.
 *
 * <p><b>Why a {@code Map} payload plus routing-key dispatch instead of two typed params.</b> One
 * queue carries two event types, and Booking's outbox relay publishes them as
 * {@code Map<String, Object>} (it parses the stored JSON and injects the outbox row id as
 * {@code eventId}), so the {@code __TypeId__} header names {@code java.util.LinkedHashMap} — and the
 * JSON converter resolves TYPE_ID ahead of the listener method's parameter type. Typed record
 * parameters could therefore never bind; the honest shape is to receive the Map, dispatch on the
 * received routing key (the binding key that delivered it), and convert to the records Notification
 * owns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // The endpoint-level flag (not the factory's) is what the registry honors: false in the
    // hermetic suite — there is no broker to connect to, and the tests drive this method
    // directly — true everywhere else.
    @RabbitListener(queues = RabbitConfig.NOTIFICATION_BOOKING_EVENTS_QUEUE,
            autoStartup = "${notification.amqp.listener.auto-startup:true}")
    public void onBookingEvent(Map<String, Object> payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        switch (routingKey) {
            case RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY -> {
                BookingConfirmedEvent event =
                        objectMapper.convertValue(payload, BookingConfirmedEvent.class);
                log.debug("Received BookingConfirmed eventId={} bookingId={}",
                        event.eventId(), event.bookingId());
                notificationService.processBookingConfirmed(event);
            }
            case RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY -> {
                BookingConfirmationRejectedEvent event =
                        objectMapper.convertValue(payload, BookingConfirmationRejectedEvent.class);
                log.debug("Received BookingConfirmationRejected eventId={} bookingId={}",
                        event.eventId(), event.bookingId());
                notificationService.processBookingConfirmationRejected(event);
            }
            default -> log.warn("Ignoring booking event with unexpected routing key '{}' — "
                    + "no binding should deliver this; check RabbitConfig", routingKey);
        }
    }
}