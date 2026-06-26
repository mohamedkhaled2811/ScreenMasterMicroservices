package com.gr74.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog service — owns movies and genres in its own Postgres (catalog-db).
 *
 * <p>A Eureka <b>client</b> (the starter auto-registers on startup; no {@code @EnableEurekaClient}
 * needed) so the gateway and Booking can discover it. Schema is managed by Liquibase with
 * {@code ddl-auto=validate} — see {@code docs/concepts/liquibase.md}.
 *
 * <p>Real {@code movies}/{@code genres} tables and the {@code GET /api/movies/{id}} endpoint arrive
 * in Phase 2 (2.1); right now the app boots against an empty (Liquibase-tracked) schema and exposes
 * only {@code /actuator/health}.
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
