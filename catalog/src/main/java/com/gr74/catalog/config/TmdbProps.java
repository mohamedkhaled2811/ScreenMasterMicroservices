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
 * <p>{@code changesEnabled} gates the <em>incremental refresh</em> pass (the {@code /movie/changes}
 * walk that re-hydrates stored movies once backfill is complete) independently of the backfill, so it
 * can be switched off without disabling ingestion. {@code changesLookbackDays} bounds how far back the
 * very first refresh reaches when there's no cursor yet — TMDB only serves ~14 days of change history,
 * so this is clamped to that ceiling.
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
        int maxPagesPerRun,
        boolean changesEnabled,
        int changesLookbackDays) {

    /** TMDB serves only about the last 14 days of changes; reaching back further returns nothing. */
    public static final int MAX_CHANGES_LOOKBACK_DAYS = 14;

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
        // Anything outside (0, MAX] is meaningless: unset/garbage falls back to the ceiling, and
        // asking for more days than TMDB serves just wastes calls — both resolve to the ceiling.
        if (changesLookbackDays <= 0 || changesLookbackDays > MAX_CHANGES_LOOKBACK_DAYS) {
            changesLookbackDays = MAX_CHANGES_LOOKBACK_DAYS;
        }
    }
}
