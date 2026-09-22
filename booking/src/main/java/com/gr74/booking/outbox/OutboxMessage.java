package com.gr74.booking.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One business fact waiting for the broker, written in the same transaction as the state change it announces.
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BOOKING_CONFIRMED | BOOKING_CONFIRMATION_REJECTED — what happened. */
    @Enumerated(EnumType.STRING) // never ordinal
    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private OutboxEventType eventType;

    /** The business row this announces ({@code bookings.id}); a logical ref, no FK. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    /** Topic routing key, e.g. {@code booking-confirmed-key}. */
    @Column(name = "routing_key", nullable = false, updatable = false, length = 120)
    private String routingKey;

    /** The event JSON exactly as it travels on the wire. Never an entity. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null while pending; set by the relay after publishing. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** W3C trace id active when the row was written; null when written outside a trace. */
    @Column(name = "trace_id", updatable = false, length = 32)
    private String traceId;

    /** Span id of the writer; becomes the traceparent's parent span id on the wire. */
    @Column(name = "span_id", updatable = false, length = 16)
    private String spanId;

    public OutboxMessage(OutboxEventType eventType, Long aggregateId, String routingKey, String payload) {
        this(eventType, aggregateId, routingKey, payload, null, null);
    }

    /** Production constructor: captures the live trace context for the relay to propagate. */
    public OutboxMessage(OutboxEventType eventType, Long aggregateId, String routingKey, String payload,
            String traceId, String spanId) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.traceId = traceId;
        this.spanId = spanId;
        this.createdAt = Instant.now();
    }

    /** Marks the row published; called only after the broker accepts it. */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** True while this row still needs delivering. */
    public boolean isPending() {
        return publishedAt == null;
    }
}