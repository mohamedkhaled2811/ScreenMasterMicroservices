package com.gr74.notification.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.notification.model.ProcessedEvent;

/**
 * Dedupe claims via raw INSERT; duplicates fail with {@code DataIntegrityViolationException}.
 * Native INSERT so assigned ids always attempt a create instead of merging.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    @Modifying
    @Query(value = "INSERT INTO processed_events (event_id, event_type, processed_at) "
            + "VALUES (:eventId, :eventType, :processedAt)", nativeQuery = true)
    int claim(@Param("eventId") long eventId, @Param("eventType") String eventType,
            @Param("processedAt") Instant processedAt);
}