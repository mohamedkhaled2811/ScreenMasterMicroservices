package com.gr74.payment.messaging;

import java.util.Map;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.RabbitConfig;
import com.gr74.payment.service.RefundService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The AMQP adapter for Booking's rejection stream — Payment's first consumer (BUILD_PLAN 3.5),
 * and the compensation loop's entry point: paid-after-expiry means the money must come back, with
 * no seat recovery and no operator.
 *
 * <p>A deliberately thin shell over {@link RefundService}, so the refund stays unit-testable
 * without a broker and this class only bridges the queue to it. No transaction, no try/catch: a
 * throw means no ack and a redelivery, safe precisely because the refund is idempotent on the
 * derived key ({@code "reject-" + eventId}) — the {@code MovieUpsertedListener} /
 * {@code PaymentEventListener} idiom verbatim. No {@code processed_events} table: the
 * {@code UNIQUE idempotency_key} constraint IS the dedupe.
 *
 * <p>Receives a {@code Map} and converts explicitly (rather than a typed record parameter) for
 * the same reason Booking's {@code PaymentEventListener} does: the publisher's
 * {@code __TypeId__} header names Booking's class, which this service does not have on its
 * classpath.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingConfirmationRejectedListener {

    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    // The endpoint-level flag (not the factory's) is what the registry honors: false in the
    // hermetic suite — there is no broker to connect to, and the tests drive this method
    // directly — true everywhere else.
    @RabbitListener(queues = RabbitConfig.BOOKING_EVENTS_QUEUE,
            autoStartup = "${payment.amqp.listener.auto-startup:true}")
    public void onRejection(Map<String, Object> payload) {
        BookingConfirmationRejectedEvent event =
                objectMapper.convertValue(payload, BookingConfirmationRejectedEvent.class);
        log.info("Received BookingConfirmationRejected bookingId={} paymentId={} reason={} eventId={} — auto-refunding",
                event.bookingId(), event.paymentId(), event.reason(), event.eventId());
        // Full remaining (amount null): the booking is gone, so every piastre comes back.
        refundService.requestRefund(
                event.paymentId(), null, event.refundReason(), event.refundIdempotencyKey());
    }
}
