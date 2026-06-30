package com.gr74.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Catalog service — owns movies and genres in its own Postgres (catalog-db).
 *
 * <p>A Eureka <b>client</b> (the starter auto-registers on startup; no {@code @EnableEurekaClient}
 * needed) so the gateway and Booking can discover it. Schema is managed by Liquibase with
 * {@code ddl-auto=validate} — see {@code docs/concepts/liquibase.md}.
 *
 * <p>Serves the read API ({@code GET /movies/{id}} detail, {@code GET /movies} paged filter) and
 * fills its own tables from TMDB via a resumable {@code @Scheduled} sync. {@code @EnableScheduling}
 * turns on the cron trigger; {@code @ConfigurationPropertiesScan} binds {@link com.gr74.catalog.config.TmdbProps}
 * ({@code TMDB_API_KEY} etc.) — mirrors {@code payment}.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
