package com.gr74.notification.security;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import com.gr74.notification.exception.NotificationErrorCode;
import com.gr74.notification.exception.NotificationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification's <b>machine identity</b>: a Keycloak access token for the service <em>itself</em>
 * (client-credentials grant).
 *
 * <p><b>Why a machine token, not the user's:</b> this service acts on background threads — a
 * {@code @RabbitListener} consuming {@code BookingConfirmed} seconds after the user's request died,
 * or a {@code @Scheduled} sweep. There is no user waiting, often no live request at all, and the
 * event deliberately carries only the {@code userId} as opaque data (a user JWT in a queue message
 * would be a credential at rest <em>and</em> expired by consume time). So the service authenticates
 * to Keycloak with its own client id/secret and gets a token scoped to what <em>it</em> may do —
 * e.g. looking up the user's email by {@code sub} (for which the {@code notification-svc}
 * client holds the least-privilege {@code view-users} realm-management role, not a realm admin).
 *
 * <p><b>No hand-rolled token cache:</b> the injected {@link OAuth2AuthorizedClientManager} (the
 * service-backed variant defined in {@code SecurityConfig}, wired from the
 * {@code notification-keycloak} registration in {@code application.yml}) fetches the token on first use and refreshes it before expiry. A naive
 * hand-rolled cache would race the refresh window under concurrent listener threads — solved code
 * stays solved.
 *
 * <p>This class provides the <em>ability</em> to obtain the token; the email lookup that spends it
 * is a separate concern. Nothing calls this provider yet — that is intentional: it establishes
 * that the service authenticates as itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MachineTokenProvider {

    /**
     * The client registration id in {@code application.yml} — the single string tying this class
     * to the {@code notification-svc} confidential client and its secret.
     */
    static final String CLIENT_REGISTRATION_ID = "notification-keycloak";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    /**
     * Returns a current access token for this service itself, fetching (and thereafter refreshing)
     * via the client-credentials grant.
     *
     * @throws NotificationException if Keycloak refuses the grant (wrong secret, unknown client) —
     *                               a coded error, never a raw {@code null} that would NPE downstream
     */
    public String accessToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                .principal("notification-svc")
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(request);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE,
                    "Could not obtain a machine token from Keycloak for '" + CLIENT_REGISTRATION_ID + "'");
        }
        log.debug("Obtained machine token (expires at {})", authorizedClient.getAccessToken().getExpiresAt());
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
