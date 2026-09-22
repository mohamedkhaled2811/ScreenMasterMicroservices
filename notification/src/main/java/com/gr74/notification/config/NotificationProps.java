package com.gr74.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables bound from the {@code notification.*} tree.
 *
 * @param mail     the From: identity stamped on outgoing messages
 * @param image    how a stored poster path becomes a full CDN URL
 * @param identity Keycloak lookup location and email cache bounds
 */
@ConfigurationProperties("notification")
public record NotificationProps(@DefaultValue Mail mail, @DefaultValue Image image,
        @DefaultValue Identity identity) {

    /**
     * The sender identity.
     *
     * @param from     the envelope From: address
     * @param fromName the display name shown instead of the raw address
     */
    public record Mail(@DefaultValue("tickets@screenmaster.local") String from,
            @DefaultValue("ScreenMaster") String fromName) {
    }

    /**
     * Poster rendering settings. Composes a full CDN URL from a stored TMDB path at render time.
     *
     * @param baseUrl    the image CDN root
     * @param posterSize a TMDB size bucket (e.g. {@code w780})
     */
    public record Image(@DefaultValue("https://image.tmdb.org/t/p") String baseUrl,
            @DefaultValue("w780") String posterSize) {

        /**
         * Builds the full CDN URL for a stored TMDB path, or {@code null} when there is no poster.
         */
        public String posterUrl(String posterPath) {
            if (posterPath == null || posterPath.isBlank()) {
                return null;
            }
            // Tolerate a path without a leading slash.
            String path = posterPath.startsWith("/") ? posterPath : "/" + posterPath;
            return baseUrl + "/" + posterSize + path;
        }
    }

    /**
     * Keycloak lookup location and email cache bounds.
     *
     * @param serverUrl         Keycloak's root
     * @param realm             the realm holding our users
     * @param emailCacheTtl     how long a resolved address is trusted
     * @param emailCacheMaxSize cache bound so it cannot grow without limit
     */
    public record Identity(@DefaultValue("http://keycloak:8180") String serverUrl,
            @DefaultValue("cinema") String realm,
            @DefaultValue("10m") Duration emailCacheTtl,
            @DefaultValue("10000") long emailCacheMaxSize) {
    }
}
