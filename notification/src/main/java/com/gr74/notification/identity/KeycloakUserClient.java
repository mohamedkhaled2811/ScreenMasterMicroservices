package com.gr74.notification.identity;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.exception.NotificationErrorCode;
import com.gr74.notification.exception.NotificationException;
import com.gr74.notification.security.MachineTokenProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a Keycloak {@code sub} into the user's email address — <b>the first and only caller of
 * {@link MachineTokenProvider}</b>, whose javadoc promised exactly this.
 *
 * <p><b>Why this lookup exists at all.</b> The booking events carry {@code userId}: an opaque
 * {@code VARCHAR(36)} that is the Keycloak {@code sub}. It is not an email, and nothing in Booking,
 * Catalog, or Payment stores one — by design, because duplicating a mutable personal detail into
 * three services is how you end up mailing three different stale addresses. Identity owns the
 * address; whoever needs it asks Identity.
 *
 * <p><b>Why a machine token and not the user's.</b> This runs on a RabbitMQ consumer thread seconds
 * after the user's HTTP request died — there is no user token to relay, and putting one in a queue
 * message would be a credential at rest that is expired by consume time anyway. So the service
 * authenticates as <em>itself</em>, with the least-privilege {@code view-users} realm-management role
 * granted to {@code notification-svc} in the realm JSON. Contrast Payment's
 * {@code UserTokenRelayInterceptor}, which relays the user's token because a user <em>is</em> waiting
 * there — two different problems, two different answers.
 *
 * <p><b>The coupling this introduces, stated honestly.</b> This is a synchronous call to Keycloak on
 * the consume path, and Phase 4 exists to remove exactly that kind of coupling. It is accepted here
 * because there is no degraded mode: you cannot email someone without their address, and a ticket
 * sent to a fallback address is worse than a ticket sent late. So a failure here SHOULD fail the
 * send — the claim rolls back, the broker redelivers with backoff, and after five attempts the
 * message dead-letters where a human can see it. The {@link Cacheable} below is what keeps that rare.
 *
 * <p>Everything else about the ticket — seats, showtime, theater, movie title, poster — is snapshotted
 * onto the event by Booking precisely so it does NOT need a call like this one. The address is the
 * single exception, because it is the one field that must be current rather than historical.
 */
@Slf4j
@Component
public class KeycloakUserClient {

    /** Cache name; see {@code CacheConfig} for the Caffeine spec bound to it. */
    public static final String EMAIL_CACHE = "user-emails";

    private final RestClient restClient;
    private final MachineTokenProvider tokenProvider;
    private final String realm;

    /**
     * @param keycloakRestClient the timeout-bounded client from {@code KeycloakClientConfig}, already
     *                           carrying Keycloak's base URL. Injected fully built rather than as a
     *                           {@code RestClient.Builder}: there IS no auto-configured builder bean
     *                           on this classpath, and publishing one would put it within reach of
     *                           Eureka's transport — see that class for the full story.
     */
    public KeycloakUserClient(RestClient keycloakRestClient, MachineTokenProvider tokenProvider,
            NotificationProps props) {
        this.tokenProvider = tokenProvider;
        this.realm = props.identity().realm();
        // The token is deliberately NOT baked in as a default header: it would be captured once at
        // construction and then served stale forever. MachineTokenProvider hands out a token that its
        // OAuth2AuthorizedClientManager refreshes before expiry, so it must be read per request.
        this.restClient = keycloakRestClient;
    }

    /**
     * The user's email address, never {@code null}.
     *
     * <p>Cached by {@code sub}: an address changes rarely, but this sits on the consume path of every
     * booking event, so without the cache a busy evening is thousands of identical Admin API calls
     * and a brief Keycloak blip fails sends that had no need to touch it.
     *
     * <p>Note what is <em>not</em> cached: the exceptions. Spring's cache only stores the returned
     * value, so a failed lookup is retried on the next delivery rather than being remembered as a
     * failure — which is what you want when the failure was "Keycloak was restarting".
     *
     * @throws NotificationException {@code NOTIFICATION_RECIPIENT_UNKNOWN} if the user has no email
     *                               (not retryable — redelivery cannot conjure an address, so it
     *                               dead-letters), or {@code NOTIFICATION_IDENTITY_UNAVAILABLE} if
     *                               Keycloak could not be reached (retryable)
     */
    @Cacheable(cacheNames = EMAIL_CACHE, key = "#userId")
    public String emailOf(String userId) {
        KeycloakUser user = fetch(userId);
        if (user == null || user.email() == null || user.email().isBlank()) {
            // Distinct from "Keycloak is down" on purpose: this one will never succeed on retry, so
            // the operator needs to see it as a data problem, not an outage.
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_RECIPIENT_UNKNOWN,
                    "Keycloak user '" + userId + "' has no email address — cannot deliver the ticket");
        }
        log.debug("Resolved email for userId={} (cached for subsequent events)", userId);
        return user.email();
    }

    /**
     * The raw Admin API call: {@code GET /admin/realms/{realm}/users/{sub}}.
     *
     * <p>Every transport failure is folded into one coded, retryable exception. A 404 is deliberately
     * NOT special-cased into "unknown recipient": a {@code sub} that came off a real booking but does
     * not resolve means the realm is misconfigured or we are pointed at the wrong Keycloak — an
     * outage-shaped problem, and retrying is the right response while it is fixed.
     */
    private KeycloakUser fetch(String userId) {
        try {
            return restClient.get()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
                    .retrieve()
                    .body(KeycloakUser.class);
        } catch (RestClientException e) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE,
                    "Could not look up user '" + userId + "' in Keycloak realm '" + realm + "'", e);
        }
    }

    /**
     * The one field we want out of Keycloak's user representation.
     *
     * <p>Keycloak returns a large object (credentials, attributes, required actions, role mappings).
     * Binding only {@code email} means the rest is ignored rather than parsed — and unknown-property
     * failures cannot happen, because Boot's ObjectMapper ignores unknowns by default. Asking for the
     * narrowest slice is also the honest expression of least privilege: we hold {@code view-users} to
     * find an address, not to mirror the user store.
     */
    record KeycloakUser(String email) {
    }
}
