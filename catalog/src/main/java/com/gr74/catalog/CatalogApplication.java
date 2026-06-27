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
 * <p>Owns the {@code movies}/{@code genres} tables and serves {@code GET /movies/{id}} (reached as
 * {@code /api/movies/{id}} through the gateway). The tables ship empty; the TMDB sync step loads rows
 * in the next phase.
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
