package com.gr74.booking.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;

/**
 * Resolves a {@link CurrentUser}-annotated {@code String} parameter to the id of the authenticated user.
 *
 * <p><b>This is the single seam between "who is calling" and the rest of Booking.</b> Every controller
 * that needs the caller's id declares {@code @CurrentUser String userId} and stays oblivious to where
 * that id comes from. It comes from the verified JWT's {@code sub} claim (Keycloak mints
 * it as the user's UUID): the resource-server filter chain has already checked the signature, expiry
 * and issuer before this code runs, so the {@code sub} read here is a trusted identity, not a
 * client assertion. Controller signatures, service signatures and the {@code bookings.user_id}
 * {@code VARCHAR(36)} column are all unchanged — a Keycloak {@code sub} lands
 * in the same column as the header value, so no migration was needed.
 *
 * <p><b>The old {@code X-User-Id} header is now IGNORED, not merely deprecated.</b> A header fallback
 * would be an authentication bypass — anyone can set {@code X-User-Id: <victim>} — so the resolver
 * never reads it, and the gateway additionally strips any inbound {@code X-User-Id} before routing.
 * A request carrying only the header and no token never reaches this code: the filter
 * chain rejects it with {@code 401 BOOKING_UNAUTHORIZED} first. The 401 thrown <em>here</em> is the
 * backstop for the one path the chain cannot see — an authenticated context that carries no JWT
 * identity (e.g. an anonymous token on a path the chain permits but a controller still annotates).
 *
 * <p>See {@code docs/concepts/current-user-resolution.md}.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        // Strictly the JWT `sub` — deliberately no fallback to any other principal type. The only
        // Authentication this service mints in production is a JwtAuthenticationToken (it is a pure
        // resource server), so anything else reaching here is a misconfiguration, and failing closed
        // with a coded 401 is the honest answer — never a silent null that would create rows under
        // a bogus user, and never the spoofable header this class used to read.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject.trim();
            }
        }
        throw new BookingException(BookingErrorCode.BOOKING_UNAUTHORIZED,
                "No authenticated user for this request — send a valid Bearer token.");
    }
}
