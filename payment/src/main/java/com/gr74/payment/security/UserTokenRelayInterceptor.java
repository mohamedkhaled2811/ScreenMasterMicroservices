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
 * Relays the caller's Bearer token onto service-to-service calls made inside the user request.
 */
@Slf4j
public class UserTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    /** Header carrying the caller's credential downstream. */
    static final String AUTHORIZATION_HEADER = HttpHeaders.AUTHORIZATION;

    /** Bearer scheme prefix; only Bearer tokens relay. */
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

    /** Current request's Bearer value, or {@code null} when no user token is present. */
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
