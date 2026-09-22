package com.gr74.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.gr74.booking.config.RabbitConfig;

import jakarta.persistence.EntityManager;

/**
 * Outbox entity round-trip on H2: pending to published, enum as string, pending count.
 */
@DataJpaTest
class OutboxMessageRepositoryTest {

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("a pending row round-trips to published, enum as a string, count follows")
    void pendingToPublishedRoundTrip() {
        OutboxMessage row = new OutboxMessage(OutboxEventType.BOOKING_CONFIRMED, 1001L,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}");

        OutboxMessage saved = outbox.saveAndFlush(row);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isPending()).isTrue();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);
        assertThat(saved.getRoutingKey()).isEqualTo(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY);
        assertThat(saved.getAggregateId()).isEqualTo(1001L);
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1L);

        em.clear(); // drop the persistence context so the re-read is a real fetch
        OutboxMessage reloaded = outbox.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isPending()).isTrue();
        assertThat(reloaded.getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);

        reloaded.markPublished();
        outbox.saveAndFlush(reloaded);
        em.clear();

        OutboxMessage published = outbox.findById(saved.getId()).orElseThrow();
        assertThat(published.isPending()).isFalse();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }
}