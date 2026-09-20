package com.gr74.payment.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import lombok.extern.slf4j.Slf4j;

/**
 * Relays the <em>user's</em> Bearer token onto a service-to-service call.
 *
 * <p><b>When this is the right mechanism:</b> the call is made <em>on behalf of the user, inside
 * the user's request</em> — Payment's {@code GET /bookings/{id}/payability} runs synchronously
 * within the user's {@code POST /payments}, asking "may <em>this person</em> pay for <em>this
 * booking</em>?". Relaying the user's own token means Booking sees the real principal and its
 * ownership answer is a genuine authorization check, not Payment's word about who's calling. The
 * alternative (Payment asserting a {@code userId} under its own machine token) is the
 * <b>confused-deputy</b> problem: a bug in Payment becomes a data leak in Booking.
 *
 * <p><b>When it is NOT:</b> background threads (schedulers, queue consumers) have no user and no
 * live {@code SecurityContext} — there the service acts <em>as itself</em> with a machine token
 * (client-credentials; see Notification). And a user JWT must <em>never</em> be written into a
 * RabbitMQ message: the queue is a durable log (a credential at rest) and the token would be
 * expired by consume time anyway — events carry the {@code userId} as opaque data instead.
 *
 * <p><b>Mechanics:</b> re-emits the current thread's user JWT as the outbound
 * {@code Authorization: Bearer …} header (rebuilt from the verified token value rather than copied
 * from the inbound header, so casing quirks never propagate). Present only for a
 * {@link JwtAuthenticationToken} — a machine-token or anonymous context relays nothing. If the user
 * has no bearer token here, the header is simply absent and Booking answers 401 — which is
 * correct: without the user's identity there is nothing to authorize against. The interceptor is
 * stateless and request-scoped by construction (it only ever reads the calling thread's context),
 * so sharing one instance across the {@code RestClient} is safe.
 */
@Slf4j
public class UserTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    /** The header carrying the caller's credential, copied verbatim downstream. */
    static final String AUTHORIZATION_HEADER = HttpHeaders.AUTHORIZATION;

    /** The scheme prefix — only Bearer tokens relay; anything else is left alone. */
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
            @NonNull ClientHttpRequestExecution execution) throws IOException {
        String bearerToken = currentBearerToken();
        if (bearerToken != null) {
            request.getHeaders().set(AUTHORIZATION_HEADER, bearerToken);
            log.debug("Relaying user token to {}", request.getURI());
        } else {
            log.debug("No user Bearer token in SecurityContext — calling {} without one",
                    request.getURI());
        }
        return execution.execute(request, body);
    }

    /**
     * The incoming request's {@code Authorization} value, or {@code null} when the current thread
     * is not serving an authenticated user request. Only a {@link JwtAuthenticationToken} qualifies:
     * relaying is about the <em>user's</em> credential, and any other authentication type (machine
     * tokens in later phases, anonymous) must never leak across the hop.
     */
    static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return null;
        }
        String tokenValue = jwtAuthentication.getToken().getTokenValue();
        if (tokenValue == null || tokenValue.isBlank()) {
            return null;
        }
        return BEARER_PREFIX + tokenValue.trim();
    }
}
