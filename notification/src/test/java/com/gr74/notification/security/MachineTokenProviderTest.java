package com.gr74.notification.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import com.gr74.notification.exception.NotificationErrorCode;
import com.gr74.notification.exception.NotificationException;

/**
 * Notification's machine identity: the service obtains its OWN token from
 * Keycloak via the client-credentials grant — no user involved.
 *
 * <p>The {@link OAuth2AuthorizedClientManager} is mocked (it is framework machinery — fetching,
 * caching and refreshing are Spring's job to get right, not ours to re-prove). What THESE cases
 * pin is our half of the contract: we ask for the right registration
 * ({@code notification-keycloak}, the {@code notification-svc} confidential client), we return the
 * token value (not the wrapper), and a refused grant becomes a coded
 * {@code NOTIFICATION_IDENTITY_UNAVAILABLE} — never a raw {@code null} that would NPE the
 * email lookup, and never a swallowed failure that would stall the queue silently.
 *
 * <p>Hermetic: no context, no Keycloak. The full-context {@code NotificationApplicationTests}
 * separately proves the registration YAML binds (the manager bean wires).
 */
class MachineTokenProviderTest {

    private final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    private final MachineTokenProvider provider = new MachineTokenProvider(manager);

    @Test
    @DisplayName("returns the machine token value granted for notification-keycloak")
    void returnsMachineTokenValue() {
        given(manager.authorize(any())).willReturn(authorizedClient("machine-token-abc"));

        assertThat(provider.accessToken()).isEqualTo("machine-token-abc");
    }

    @Test
    @DisplayName("asks for the notification-keycloak registration (our own client, not a user's)")
    void asksForOwnRegistration() {
        given(manager.authorize(any())).willReturn(authorizedClient("machine-token-abc"));

        provider.accessToken();

        org.mockito.ArgumentCaptor<org.springframework.security.oauth2.client.OAuth2AuthorizeRequest> request =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.oauth2.client.OAuth2AuthorizeRequest.class);
        org.mockito.Mockito.verify(manager).authorize(request.capture());
        assertThat(request.getValue().getClientRegistrationId())
                .isEqualTo("notification-keycloak");
    }

    @Test
    @DisplayName("a refused grant is a coded NOTIFICATION_IDENTITY_UNAVAILABLE, never a null")
    void refusedGrantIsCoded() {
        given(manager.authorize(any())).willReturn(null);

        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).errorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE);
    }

    private static OAuth2AuthorizedClient authorizedClient(String tokenValue) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("notification-keycloak")
                .clientId("notification-svc")
                .clientSecret("notification-svc-dev-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://keycloak:8180/realms/cinema/protocol/openid-connect/token")
                .scope("openid")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, tokenValue,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(290),
                Set.of("openid"));
        return new OAuth2AuthorizedClient(registration, "notification-svc", accessToken);
    }
}
