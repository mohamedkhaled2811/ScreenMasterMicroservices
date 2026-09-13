package com.gr74.notification.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.notification.model.ProcessedEvent;

/**
 * The dedupe table's repository. The claim is the {@link #claim(long, String, Instant)} insert:
 * it either creates the row (this consumer won the event) or fails with a
 * {@code DataIntegrityViolationException} (someone else already claimed it) — never a
 * read-then-insert, so the race window does not exist.
 *
 * <p><b>Why a raw INSERT and not {@code saveAndFlush}.</b> {@code ProcessedEvent}'s PK is the
 * inbound event id — an <em>assigned</em> id, not a generated one. Spring Data's {@code save()}
 * treats an entity with an assigned id as "not new" and {@code merge()}s it, so re-saving the same
 * eventId silently becomes an UPDATE (or a no-op on the already-managed row) instead of failing the
 * insert — the dedupe would never fire. A native INSERT always attempts to create the row, so a
 * duplicate reliably violates the PK; the repository proxy translates that into Spring's
 * {@code DataIntegrityViolationException} for the service to catch.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    @Modifying
    @Query(value = "INSERT INTO processed_events (event_id, event_type, processed_at) "
            + "VALUES (:eventId, :eventType, :processedAt)", nativeQuery = true)
    int claim(@Param("eventId") long eventId, @Param("eventType") String eventType,
            @Param("processedAt") Instant processedAt);
}