package com.gr74.notification.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.exception.NotificationErrorCode;
import com.gr74.notification.exception.NotificationException;
import com.gr74.notification.security.MachineTokenProvider;

/**
 * The recipient lookup — the one synchronous cross-service call left on the consume path, and the
 * first caller of {@link MachineTokenProvider}.
 *
 * <p>What matters here is the <b>error mapping</b>, because the two failure kinds need opposite
 * handling downstream:
 * <ul>
 *   <li>a user with <em>no email</em> can never succeed on retry → it must dead-letter, not loop;</li>
 *   <li>Keycloak being <em>unreachable</em> will succeed later → it must retry.</li>
 * </ul>
 * Collapsing those two into one exception is how you get either a hot loop on bad data or a silently
 * dropped ticket on a blip.
 *
 * <p>Note what is <b>not</b> tested here: the {@code @Cacheable}. Spring's cache is a proxy applied
 * to the bean, so a unit-constructed instance has no caching at all — asserting on it here would be
 * asserting on nothing. The cache's configuration lives in {@code CacheConfig} and is Spring's code;
 * what would actually break is the cache <em>name</em> mismatching, which fails loudly at startup.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakUserClientTest {

    private static final String BASE = "http://keycloak:8180";
    private static final String SUB = "11111111-1111-1111-1111-111111111111";

    @Mock
    private MachineTokenProvider tokenProvider;

    private MockRestServiceServer server;
    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KeycloakUserClient(builder.build(), tokenProvider, new NotificationProps(
                new NotificationProps.Mail("tickets@screenmaster.local", "ScreenMaster"),
                new NotificationProps.Image("https://image.tmdb.org/t/p", "w780"),
                new NotificationProps.Identity(BASE, "cinema", Duration.ofMinutes(10), 10_000)));
    }

    @Test
    @DisplayName("resolves the email and presents the machine token as a Bearer credential")
    void resolvesEmailWithTheMachineToken() {
        given(tokenProvider.accessToken()).willReturn("machine-token-abc");
        server.expect(requestTo(BASE + "/admin/realms/cinema/users/" + SUB))
                .andExpect(method(HttpMethod.GET))
                // The service authenticates AS ITSELF here — not as the user, who is long gone by
                // the time this consumer thread runs.
                .andExpect(header("Authorization", "Bearer machine-token-abc"))
                .andRespond(withSuccess("{\"email\":\"alice@screenmaster.local\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.emailOf(SUB)).isEqualTo("alice@screenmaster.local");
        server.verify();
    }

    @Test
    @DisplayName("a user with no email is NOT retryable — it must dead-letter, not loop forever")
    void userWithoutAnEmailIsACodedNonRetryableFailure() {
        given(tokenProvider.accessToken()).willReturn("machine-token-abc");
        // Keycloak knows the user; the email field is simply absent.
        server.expect(requestTo(BASE + "/admin/realms/cinema/users/" + SUB))
                .andRespond(withSuccess("{\"username\":\"alice\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.emailOf(SUB))
                .isInstanceOf(NotificationException.class)
                .extracting("errorCode")
                .isEqualTo(NotificationErrorCode.NOTIFICATION_RECIPIENT_UNKNOWN);
        server.verify();
    }

    @Test
    @DisplayName("Keycloak unreachable maps to the retryable identity-unavailable code")
    void outageIsRetryable() {
        given(tokenProvider.accessToken()).willReturn("machine-token-abc");
        server.expect(requestTo(BASE + "/admin/realms/cinema/users/" + SUB))
                .andRespond(withServerError());

        // Distinct code from the one above: this WILL succeed on retry, so the message must go back
        // on the queue rather than to the DLQ.
        assertThatThrownBy(() -> client.emailOf(SUB))
                .isInstanceOf(NotificationException.class)
                .extracting("errorCode")
                .isEqualTo(NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("a 404 is treated as an outage, not as a bad recipient")
    void unknownSubIsTreatedAsAnOutage() {
        given(tokenProvider.accessToken()).willReturn("machine-token-abc");
        server.expect(requestTo(BASE + "/admin/realms/cinema/users/" + SUB))
                .andRespond(withResourceNotFound());

        // Deliberate: a sub that came off a REAL booking but does not resolve means we are pointed at
        // the wrong realm or the wrong Keycloak — a misconfiguration to retry through while it is
        // fixed, not a customer whose ticket should be abandoned.
        assertThatThrownBy(() -> client.emailOf(SUB))
                .isInstanceOf(NotificationException.class)
                .extracting("errorCode")
                .isEqualTo(NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE);
        server.verify();
    }
}
