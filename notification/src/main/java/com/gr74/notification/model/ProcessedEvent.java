package com.gr74.notification.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The idempotent-consumer dedupe row — one entry per event id this service has already processed.
 *
 * <p><b>{@code eventId} is an ASSIGNED PK</b> — the <em>inbound</em> event's id (Booking's outbox row
 * id), never one we mint. No {@code @GeneratedValue}: the whole point is that a redelivered event
 * carries the same id as its first delivery, so the insert collides and the PK constraint rejects the
 * duplicate. That constraint IS the dedupe — there is no read-then-insert, so two concurrent consumer
 * instances racing the same event cannot both pass a check and both send.
 *
 * <p>Schema owned by Liquibase ({@code ddl-auto=validate}); must match
 * {@code 001-create-processed-events.yaml}.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent(long eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }
}