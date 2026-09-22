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
 * Provides this service's own Keycloak access token via client-credentials.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MachineTokenProvider {

    /** Client registration id in {@code application.yml}. */
    static final String CLIENT_REGISTRATION_ID = "notification-keycloak";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    /**
     * Returns a current access token for this service.
     *
     * @throws NotificationException if Keycloak refuses the grant
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
