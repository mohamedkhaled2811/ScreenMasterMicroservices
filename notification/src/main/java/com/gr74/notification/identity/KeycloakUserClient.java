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

/** Resolves a Keycloak user id to its email address, cached by user id. */
@Slf4j
@Component
public class KeycloakUserClient {

    /** Email cache name. */
    public static final String EMAIL_CACHE = "user-emails";

    private final RestClient restClient;
    private final MachineTokenProvider tokenProvider;
    private final String realm;

    /** Creates the client around the Keycloak REST client. */
    public KeycloakUserClient(RestClient keycloakRestClient, MachineTokenProvider tokenProvider,
            NotificationProps props) {
        this.tokenProvider = tokenProvider;
        this.realm = props.identity().realm();
        // Token read per request so refreshes apply.
        this.restClient = keycloakRestClient;
    }

    /**
     * Returns the user's email address, cached by user id.
     *
     * @throws NotificationException {@code NOTIFICATION_RECIPIENT_UNKNOWN} if the user has no email,
     *                               or {@code NOTIFICATION_IDENTITY_UNAVAILABLE} if Keycloak is unreachable
     */
    @Cacheable(cacheNames = EMAIL_CACHE, key = "#userId")
    public String emailOf(String userId) {
        KeycloakUser user = fetch(userId);
        if (user == null || user.email() == null || user.email().isBlank()) {
            // Missing email is a data problem, not an outage.
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_RECIPIENT_UNKNOWN,
                    "Keycloak user '" + userId + "' has no email address — cannot deliver the ticket");
        }
        log.debug("Resolved email for userId={} (cached for subsequent events)", userId);
        return user.email();
    }

    /** Calls {@code GET /admin/realms/{realm}/users/{id}}; transport failures become a retryable coded exception. */
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

    /** Keycloak user email. */
    record KeycloakUser(String email) {
    }
}
