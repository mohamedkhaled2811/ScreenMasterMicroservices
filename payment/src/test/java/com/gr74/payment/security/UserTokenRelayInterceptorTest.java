package com.gr74.payment.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Payment's user-token relay onto the Booking {@code payability} read
 * — proven at the HTTP level, not just as string manipulation.
 *
 * <p>Each case builds a {@code RestClient} the way {@code BookingClientConfig} does (relay
 * interceptor attached), serves the current thread a forged {@link JwtAuthenticationToken} the way
 * the resource-server chain would, and asserts on the wire: the outbound call to Booking either
 * carries {@code Authorization: Bearer <the user's token>} or carries no such header. A
 * {@link MockRestServiceServer} stands in for Booking, so no Eureka, no Booking, no Keycloak.
 *
 * <p>Why this test matters: without the relay, Booking's "caller owns this booking" guard compares
 * against whatever Payment <em>claims</em> — the confused-deputy hole. With it, Booking sees the
 * verified user. If the interceptor ever stops attaching the header, the payability call 401s and
 * every checkout breaks loudly — this test is the tripwire.
 */
class UserTokenRelayInterceptorTest {

    private static final String PAYABILITY_URL = "lb://booking/bookings/1001/payability";
    private static final String USER_TOKEN = "user-jwt-value";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the user's Bearer token rides along to Booking")
    void relaysUserBearerToken() {
        authenticateAsUser(USER_TOKEN);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("lb://booking")
                .requestInterceptor(new UserTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + USER_TOKEN))
                .andRespond(withSuccess("""
                        {"bookingId":1001,"userId":"user-1","status":"PENDING",
                         "expiresAt":"2026-08-31T20:15:00Z","totalAmount":300.00,"currency":"EGP"}""",
                        MediaType.APPLICATION_JSON));

        builder.build().get().uri("/bookings/{id}/payability", 1001L)
                .retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("with no user in the SecurityContext, no Authorization header is attached")
    void noUserMeansNoHeader() {
        // No authentication at all — e.g. a background thread. The call goes out bare and Booking
        // answers 401, which is correct: without the user's identity there is nothing to authorize.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("lb://booking")
                .requestInterceptor(new UserTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}",
                        MediaType.APPLICATION_JSON));

        builder.build().get().uri("/bookings/{id}/payability", 1001L)
                .retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("a non-JWT authentication is never relayed (machine tokens stay home)")
    void nonJwtAuthenticationIsNotRelayed() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken("svc", null));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("lb://booking")
                .requestInterceptor(new UserTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().get().uri("/bookings/{id}/payability", 1001L)
                .retrieve().toBodilessEntity();

        server.verify();
    }

    /** Serves the current thread the authentication the resource-server chain would have built. */
    private static void authenticateAsUser(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static org.springframework.test.web.client.RequestMatcher headerDoesNotExist(String name) {
        // NOTE Spring 7: HttpHeaders no longer implements Map (no containsKey) — read via getFirst.
        return request -> assertThat(request.getHeaders().getFirst(name)).isNull();
    }
}
