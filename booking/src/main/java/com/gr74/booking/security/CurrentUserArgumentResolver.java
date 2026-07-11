package com.gr74.booking.security;

import org.springframework.core.MethodParameter;
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
 * that id comes from. Today it comes from the {@code X-User-Id} header; a missing or blank header is a
 * coded {@code BOOKING_VALIDATION_ERROR} (400) — never a raw 500, and never a silent {@code null} that
 * would let an unauthenticated request create rows under a bogus user.
 *
 * <p><b>Phase-7 change (Keycloak):</b> replace the header read below with
 * {@code SecurityContextHolder.getContext().getAuthentication()} / the JWT {@code sub} claim (and turn
 * "missing" into a 401 instead of a 400). Nothing outside this class changes — that's the point of
 * funnelling identity through one resolver. See {@code docs/concepts/current-user-resolution.md}.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /** The stand-in for the JWT until Keycloak lands in Phase 7. */
    static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            // In Phase 7 an absent identity becomes a 401 at the resource server; pre-auth it's the
            // caller failing to supply the required header, so a coded 400 is the honest answer.
            throw new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                    "Missing required '" + USER_ID_HEADER + "' header identifying the user.");
        }
        return userId.trim();
    }
}
