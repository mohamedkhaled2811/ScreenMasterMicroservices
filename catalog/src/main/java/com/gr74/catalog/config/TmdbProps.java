package com.gr74.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code tmdb.*} config keys (see {@code application.yml}).
 *
 * <p>The {@code accessToken} is TMDB's v4 "API Read Access Token", sent as
 * {@code Authorization: Bearer <token>} — wired from the {@code TMDB_API_KEY} env var so the secret
 * is never committed (project convention). {@code enabled} gates the scheduled sync (off in tests so
 * they stay hermetic, on for local/compose); {@code cron} sets its cadence. {@code maxPagesPerRun}
 * bounds how many list pages a single tick walks, our own back-pressure against TMDB's rate limit.
 *
 * <p>A {@code record} is the idiomatic immutable holder for config; binding is constructor-based.
 * Mirrors {@code payment}'s {@code PaymentProps}. See
 * {@code docs/concepts/spring-boot-annotations.md} (@ConfigurationProperties).
 */
@ConfigurationProperties(prefix = "tmdb")
public record TmdbProps(
        String accessToken,
        String baseUrl,
        String imageBaseUrl,
        boolean enabled,
        String cron,
        int maxPagesPerRun) {

    public TmdbProps {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.themoviedb.org/3";
        }
        if (imageBaseUrl == null || imageBaseUrl.isBlank()) {
            imageBaseUrl = "https://image.tmdb.org/t/p";
        }
        if (cron == null || cron.isBlank()) {
            cron = "0 0 * * * *"; // hourly
        }
        if (maxPagesPerRun <= 0) {
            maxPagesPerRun = 10;
        }
    }
}
