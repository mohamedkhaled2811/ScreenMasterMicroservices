package com.gr74.payment.outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.OutboxProps;
import com.gr74.payment.config.RabbitConfig;
import com.gr74.payment.service.OutboxWriter;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains pending outbox rows to the broker (publish-then-mark, so delivery is at-least-once).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxWriter writer;
    private final RabbitTemplate rabbit;
    private final OutboxProps props;
    private final ObjectMapper objectMapper;

    /** Drains pending rows in bounded batches until the backlog is empty. */
    @Observed(name = "outbox.drain", contextualName = "drain-outbox")
    @Scheduled(fixedDelayString = "${payment.outbox.relay-interval-millis:2000}")
    void drain() {
        try {
            while (true) {
                List<OutboxMessage> batch = writer.claimBatch(props.relayBatchSize());
                if (batch.isEmpty()) {
                    return;
                }
                for (OutboxMessage message : batch) {
                    rabbit.convertAndSend(
                            RabbitConfig.EXCHANGE, message.getRoutingKey(), payloadWithEventId(message),
                            traceparent(message));
                    writer.markPublished(message); // AFTER publish — never before.
                }
                log.debug("Relay published {} outbox row(s)", batch.size());
            }
        } catch (AmqpException e) {
            // Broker down: rows stay pending for the next tick.
            log.warn("Outbox relay deferred: broker unavailable — rows stay pending for the next tick", e);
        }
    }

    /** Stamps the persisted trace onto the outgoing message as a W3C {@code traceparent} header. */
    private MessagePostProcessor traceparent(OutboxMessage message) {
        String traceId = message.getTraceId();
        String spanId = message.getSpanId();
        if (traceId == null || spanId == null) {
            return m -> m;
        }
        String header = "00-" + traceId + "-" + spanId + "-01";
        return m -> {
            m.getMessageProperties().setHeader("traceparent", header);
            return m;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadWithEventId(OutboxMessage message) {
        try {
            Map<String, Object> payload =
                    objectMapper.readValue(message.getPayload(), LinkedHashMap.class);
            payload.put("eventId", message.getId());
            return payload;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Outbox row " + message.getId() + " holds unparseable payload", e);
        }
    }
}
