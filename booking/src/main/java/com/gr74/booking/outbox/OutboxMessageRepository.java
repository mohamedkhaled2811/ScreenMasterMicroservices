package com.gr74.booking.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link OutboxMessage}.
 *
 * <p>The drain query is native Postgres on purpose: JPA has no portable {@code FOR UPDATE SKIP
 * LOCKED}, and skipping locked rows is what lets two relay instances (or two ticks overlapping a
 * slow broker) share the backlog without blocking on each other. H2 has no {@code SKIP LOCKED}, so
 * this query never runs in the hermetic suite — the relay is unit-tested against a mocked claim,
 * and the boundary test proves the row commits and rolls back with the business write. Mirrors
 * {@code payment}'s {@code OutboxMessageRepository}.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claim up to {@code limit} unpublished rows, oldest first, skipping rows another claimer
     * already holds. Must run inside a transaction ({@code FOR UPDATE} needs one); the locks are
     * held only for the claim itself — publishing happens after, row by row.
     */
    @Query(value = """
            SELECT * FROM outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimPending(@Param("limit") int limit);

    /** How many rows are still waiting — the lag signal worth alerting on. */
    long countByPublishedAtIsNull();
}