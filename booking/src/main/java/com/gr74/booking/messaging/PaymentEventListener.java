package com.gr74.booking.messaging;

import java.util.Map;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.BookingConfirmer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The AMQP adapter for Payment's outcome stream — a deliberately thin shell over
 * {@link BookingConfirmer}. All the interesting behaviour (the conditional-update confirm, the
 * idempotent re-read, the failure mirror) lives in the confirmer so it is unit-testable without
 * a broker; this class only bridges the queue to it.
 *
 * <p>An exception thrown here (e.g. the DB is momentarily down) propagates, the message is
 * <em>not</em> acked, and RabbitMQ redelivers it — safe precisely because the confirmer is
 * idempotent. No transaction, no try/catch: the {@code MovieUpsertedListener} idiom.
 *
 * <p><b>Why a {@code Map} payload plus routing-key dispatch instead of two typed params.</b> One
 * queue carries two event types, and Payment's outbox relay publishes them as
 * {@code Map<String, Object>} (it parses the stored JSON and injects the outbox row id as
 * {@code eventId}), so the {@code __TypeId__} header names {@code java.util.LinkedHashMap} — and
 * the JSON converter resolves TYPE_ID ahead of the listener method's parameter type (verified by
 * probe: even a hinted conversion returns the Map). Typed record parameters could therefore never
 * bind; the honest shape is to receive the Map, dispatch on the received routing key (the binding
 * key that delivered it), and convert to the record Booking owns. As a side effect this also
 * sidesteps the cross-package {@code __TypeId__} trust trap typed consumption falls into.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final BookingConfirmer confirmer;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitConfig.PAYMENT_EVENTS_QUEUE)
    public void onPaymentEvent(Map<String, Object> payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        switch (routingKey) {
            case RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY -> {
                PaymentSucceededEvent event =
                        objectMapper.convertValue(payload, PaymentSucceededEvent.class);
                log.debug("Received PaymentSucceeded paymentId={} bookingId={} eventId={}",
                        event.paymentId(), event.bookingId(), event.eventId());
                confirmer.confirmFromPayment(event);
            }
            case RabbitConfig.PAYMENT_FAILED_ROUTING_KEY -> {
                PaymentFailedEvent event =
                        objectMapper.convertValue(payload, PaymentFailedEvent.class);
                log.debug("Received PaymentFailed paymentId={} bookingId={} eventId={}",
                        event.paymentId(), event.bookingId(), event.eventId());
                confirmer.mirrorFailure(event);
            }
            default -> log.warn("Ignoring payment event with unexpected routing key '{}' — "
                    + "no binding should deliver this; check RabbitConfig", routingKey);
        }
    }
}
