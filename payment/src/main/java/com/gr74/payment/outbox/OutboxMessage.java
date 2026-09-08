package com.gr74.payment.outbox;

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
 * One business fact waiting for the broker — the transactional outbox (BUILD_PLAN 3.2, see
 * {@code docs/concepts/transactional-outbox.md}).
 *
 * <p>The row is written <b>in the same transaction as the state change it announces</b> (a payment
 * going {@code PAID} and its {@code PaymentSucceeded} row are one commit), so the dual-write
 * problem — state committed but event lost, or event published but state rolled back — cannot
 * happen. A scheduled relay ({@link OutboxRelay}) publishes pending rows and marks them, which is
 * what makes delivery at-least-once: publish-then-mark means a crash between the two re-publishes
 * (safe, consumers are idempotent), while mark-then-publish could lose the event forever.
 *
 * <p>Shaped so the relay can later be extracted into a shared implementation without a migration:
 * generic event type, routing key, and JSON payload rather than payment-specific columns.
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PAYMENT_SUCCEEDED | PAYMENT_FAILED — what happened, in our vocabulary. */
    @Enumerated(EnumType.STRING) // never ordinal
    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private OutboxEventType eventType;

    /** The business row this announces ({@code payments.id}) — a logical ref, deliberately no FK. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    /** Topic routing key, e.g. {@code payment-succeeded-key} — the contract Booking binds to. */
    @Column(name = "routing_key", nullable = false, updatable = false, length = 120)
    private String routingKey;

    /** The event JSON exactly as it must travel on the wire. Never an entity. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null while pending; the relay sets it after publishing. Indexed for the drain query. */
    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxMessage(OutboxEventType eventType, Long aggregateId, String routingKey, String payload) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    /** Mark this row published — called only AFTER the broker accepted it, never before. */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** True while this row still needs delivering. */
    public boolean isPending() {
        return publishedAt == null;
    }
}
