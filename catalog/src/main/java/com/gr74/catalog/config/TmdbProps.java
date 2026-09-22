package com.gr74.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code tmdb.*} config keys.
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

    /** TMDB serves only about the last 14 days of changes. */
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
        // Clamp to (0, MAX]; out-of-range values fall back to the ceiling.
        if (changesLookbackDays <= 0 || changesLookbackDays > MAX_CHANGES_LOOKBACK_DAYS) {
            changesLookbackDays = MAX_CHANGES_LOOKBACK_DAYS;
        }
    }
}
