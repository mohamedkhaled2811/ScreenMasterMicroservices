package com.gr74.payment.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.WebhookEvent;

/**
 * Spring Data repository for {@link WebhookEvent}. Dedupe is the UNIQUE (gateway, event_id) insert, not a prior read.
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByGatewayAndEventId(PaymentGatewayType gateway, String eventId);

    /** Retention: null payloads of old deliveries, keeping rows so dedupe still holds. */
    @Modifying
    @Query("""
            update WebhookEvent w
               set w.payload = '', w.headers = null
             where w.receivedAt < :cutoff
               and w.payload <> ''
            """)
    int prunePayloadsOlderThan(@Param("cutoff") Instant cutoff);
}
