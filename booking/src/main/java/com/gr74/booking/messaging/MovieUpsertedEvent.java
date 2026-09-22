package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Booking's local copy of Catalog's {@code MovieUpserted} event, matched by field name during JSON deserialization.
 */
public record MovieUpsertedEvent(
        Long id,
        String title,
        String posterPath,
        Instant updatedAt,
        String eventId) {
}
