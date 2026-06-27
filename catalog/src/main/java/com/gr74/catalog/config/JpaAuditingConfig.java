package com.gr74.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on Spring Data JPA auditing so {@code @CreatedDate} / {@code @LastModifiedDate} on
 * {@link com.gr74.catalog.model.Movie} are populated automatically on insert/update. Without this,
 * the {@code created_date} column (NOT NULL in the schema) would be left null and the insert would
 * fail. Kept as a dedicated config class so the smoke test could exclude it if ever needed.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
