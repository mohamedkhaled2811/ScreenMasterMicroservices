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
 * One business fact waiting for the broker — the transactional outbox (BUILD_PLAN 4.1/4.2, see
 * {@code docs/concepts/transactional-outbox.md}).
 *
 * <p>The row is written <b>in the same transaction as the state change it announces</b> (a booking
 * going {@code CONFIRMED} and its {@code BookingConfirmed} row are one commit), so the dual-write
 * problem — state committed but event lost, or event published but state rolled back — cannot
 * happen. A scheduled relay ({@link OutboxRelay}) publishes pending rows and marks them, which is
 * what makes delivery at-least-once: publish-then-mark means a crash between the two re-publishes
 * (safe, consumers are idempotent), while mark-then-publish could lose the event forever. For a
 * lost {@code BookingConfirmationRejected} that loss is a stranded customer refund — the exact gap
 * {@code BookingEventPublisher} carried through Phase 3, now closed.
 *
 * <p>Mirrors {@code payment}'s {@code OutboxMessage} file-for-file (no shared module — the same
 * deliberate duplication as the routing-key constants in {@code RabbitConfig}).
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BOOKING_CONFIRMED | BOOKING_CONFIRMATION_REJECTED — what happened, in our vocabulary. */
    @Enumerated(EnumType.STRING) // never ordinal
    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private OutboxEventType eventType;

    /** The business row this announces ({@code bookings.id}) — a logical ref, deliberately no FK. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    /** Topic routing key, e.g. {@code booking-confirmed-key} — the contract consumers bind to. */
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