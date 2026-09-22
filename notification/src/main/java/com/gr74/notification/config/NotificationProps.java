package com.gr74.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This service's own tunables, bound from the {@code notification.*} tree in {@code application.yml}
 * (the repo's standing {@code @ConfigurationProperties} convention — see {@code payment}'s props as
 * the reference).
 *
 * <p>A record, so every value is final and validated once at startup rather than read (and possibly
 * null) on some listener thread at 3am. A typo'd property name fails the boot, not the hundredth
 * booking.
 *
 * @param mail     the From: identity stamped on every outgoing message
 * @param image    how a stored TMDB poster <em>path</em> becomes a full CDN <em>URL</em>
 * @param identity where user emails are looked up, and how long to remember them
 */
@ConfigurationProperties("notification")
public record NotificationProps(@DefaultValue Mail mail, @DefaultValue Image image,
        @DefaultValue Identity identity) {

    /**
     * The sender identity.
     *
     * @param from     the envelope From: address — a real provider only accepts a domain you own
     * @param fromName the display name a mail client shows instead of the raw address
     */
    public record Mail(@DefaultValue("tickets@screenmaster.local") String from,
            @DefaultValue("ScreenMaster") String fromName) {
    }

    /**
     * Poster rendering.
     *
     * <p><b>Why the base URL and size live here and not in the snapshotted data.</b> The event
     * carries TMDB's <em>path</em> ({@code /abc.jpg}) exactly as Catalog stores it. The CDN host and
     * the image width are a <em>rendering</em> decision — baking a full URL into stored data would
     * mean re-syncing every movie to change the image size, or to move CDNs. Composing
     * {@code {baseUrl}/{posterSize}{path}} at render time keeps that reversible.
     *
     * @param baseUrl    TMDB's image CDN root, the same value catalog's {@code TMDB_IMAGE_BASE_URL}
     *                   pins
     * @param posterSize a TMDB size bucket ({@code w342}, {@code w500}, {@code w780}, {@code original});
     *                   {@code w780} suits a ~600px-wide email body on a retina display
     */
    public record Image(@DefaultValue("https://image.tmdb.org/t/p") String baseUrl,
            @DefaultValue("w780") String posterSize) {

        /**
         * Builds the full CDN URL for a stored TMDB path, or {@code null} when the movie has no
         * poster.
         *
         * <p>Null is a real case — TMDB genuinely lacks artwork for some titles — and it is the
         * caller's cue to render the ticket without the image band. The template must cope with that
         * anyway, because most mail clients block remote images by default.
         */
        public String posterUrl(String posterPath) {
            if (posterPath == null || posterPath.isBlank()) {
                return null;
            }
            // TMDB paths are stored with their leading slash ("/abc.jpg"); tolerate one that isn't.
            String path = posterPath.startsWith("/") ? posterPath : "/" + posterPath;
            return baseUrl + "/" + posterSize + path;
        }
    }

    /**
     * Where the recipient's address comes from.
     *
     * @param serverUrl         Keycloak's root — the Admin API lives at
     *                          {@code {serverUrl}/admin/realms/{realm}/users/{sub}}
     * @param realm             the realm holding our users ({@code cinema})
     * @param emailCacheTtl     how long a resolved address is trusted; short enough that a user who
     *                          changes their email sees it take effect the same session, long enough
     *                          that a burst of bookings costs one lookup
     * @param emailCacheMaxSize bounds the cache so it can never become an unbounded leak
     */
    public record Identity(@DefaultValue("http://keycloak:8180") String serverUrl,
            @DefaultValue("cinema") String realm,
            @DefaultValue("10m") Duration emailCacheTtl,
            @DefaultValue("10000") long emailCacheMaxSize) {
    }
}
