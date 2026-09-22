package com.gr74.booking.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link OutboxMessage}.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claims up to {@code limit} unpublished rows, oldest first, skipping locked rows.
     * Must run inside a transaction.
     */
    @Query(value = """
            SELECT * FROM outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimPending(@Param("limit") int limit);

    /** Counts rows still waiting to publish. */
    long countByPublishedAtIsNull();
}