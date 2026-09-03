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
 * Spring Data repository for {@link WebhookEvent}.
 *
 * <p>Note there is no "exists then insert" helper: the dedupe is the {@code UNIQUE (gateway,
 * event_id)} constraint failing on insert, not a prior read. A read-then-write would leave a window
 * in which two concurrent deliveries of the same event both pass the check.
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByGatewayAndEventId(PaymentGatewayType gateway, String eventId);

    /**
     * Retention: drop the stored bodies of old deliveries.
     *
     * <p>Deliberately nulls the payload and headers rather than deleting the row, so the
     * {@code (gateway, event_id)} dedupe key survives — an ancient redelivery still cannot
     * double-process after its body has been pruned. Storing personal data (billing name, email,
     * last-4) forever is not a defensible default.
     */
    @Modifying
    @Query("""
            update WebhookEvent w
               set w.payload = '', w.headers = null
             where w.receivedAt < :cutoff
               and w.payload <> ''
            """)
    int prunePayloadsOlderThan(@Param("cutoff") Instant cutoff);
}
