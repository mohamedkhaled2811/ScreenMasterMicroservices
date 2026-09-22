package com.gr74.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables JPA auditing for {@code @CreatedDate} / {@code @LastModifiedDate}. */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
