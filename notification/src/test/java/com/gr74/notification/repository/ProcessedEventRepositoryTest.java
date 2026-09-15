package com.gr74.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.gr74.notification.model.ProcessedEvent;

/**
 * The dedupe table's persistence contract.
 *
 * <p>Runs on H2 with Hibernate generating the schema (see {@code src/test/resources/application.yml}),
 * so it proves the <em>entity</em> agrees with itself. The Liquibase changeset is the production
 * schema, and {@code ddl-auto=validate} is what proves the two agree — that check runs on a real
 * Postgres boot, not here.
 */
@DataJpaTest
class ProcessedEventRepositoryTest {

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Test
    @DisplayName("a claimed event round-trips with its event id, type and timestamp")
    void roundTrips() {
        Instant processedAt = Instant.parse("2026-09-13T12:00:00Z");

        processedEvents.claim(7L, "BOOKING_CONFIRMED", processedAt);

        ProcessedEvent reloaded = processedEvents.findById(7L).orElseThrow();
        assertThat(reloaded.getEventId()).isEqualTo(7L);
        assertThat(reloaded.getEventType()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(reloaded.getProcessedAt()).isEqualTo(processedAt);
    }

    @Test
    @DisplayName("a duplicate event id violates the PK — the dedupe IS the insert")
    void duplicateEventIdViolatesTheConstraint() {
        processedEvents.claim(8L, "BOOKING_CONFIRMED", Instant.now());

        // A redelivery (or a second consumer) re-inserts the same key; the constraint rejects it and
        // the service translates that rejection into a no-op. Note this MUST be a genuine INSERT:
        // Spring Data's save() would merge an assigned-id entity into a silent UPDATE instead.
        assertThatThrownBy(() -> processedEvents.claim(8L, "BOOKING_CONFIRMED", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}