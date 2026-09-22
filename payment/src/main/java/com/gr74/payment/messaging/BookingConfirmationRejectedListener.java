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
 * Consumes booking rejections and triggers a full auto-refund via {@link RefundService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingConfirmationRejectedListener {

    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitConfig.BOOKING_EVENTS_QUEUE,
            autoStartup = "${payment.amqp.listener.auto-startup:true}")
    public void onRejection(Map<String, Object> payload) {
        BookingConfirmationRejectedEvent event =
                objectMapper.convertValue(payload, BookingConfirmationRejectedEvent.class);
        log.info("Received BookingConfirmationRejected bookingId={} paymentId={} reason={} eventId={} — auto-refunding",
                event.bookingId(), event.paymentId(), event.reason(), event.eventId());
        // Full remaining refund (amount null): the booking is gone, so everything comes back.
        refundService.requestRefund(
                event.paymentId(), null, event.refundReason(), event.refundIdempotencyKey());
    }
}
