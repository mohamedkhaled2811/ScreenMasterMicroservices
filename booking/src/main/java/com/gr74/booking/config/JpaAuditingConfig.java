package com.gr74.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on Spring Data JPA auditing so {@code @CreatedDate} / {@code @LastModifiedDate} on the
 * inventory entities ({@link com.gr74.booking.model.Theater}, {@code Screen}, {@code Seat},
 * {@code SeatType}, {@code Showtime}) are populated automatically on insert/update. Without this, the
 * {@code created_date} columns (NOT NULL in the schema) would be left null and the insert would fail.
 *
 * <p>Kept as a dedicated config class (mirrors {@code catalog}'s twin) so a {@code @DataJpaTest} can
 * {@code @Import} it — the slice doesn't load {@code @Configuration} beans automatically.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
