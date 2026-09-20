package com.gr74.payment.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;

/**
 * Resolves a {@link CurrentUser}-annotated {@code String} parameter to the id of the authenticated user.
 *
 * <p><b>This is the single seam between "who is calling" and the rest of Payment.</b> Every
 * controller that needs the caller's id declares {@code @CurrentUser String userId}: the id comes
 * from the verified JWT's {@code sub} claim (Keycloak mints it as the user's UUID), which the
 * resource-server filter chain has already signature-checked before this code runs. Controller and
 * service signatures take a plain {@code String userId}; the <em>source</em> of that string is a
 * verified token claim, never a spoofable {@code X-User-Id} header.
 *
 * <p><b>The old {@code X-User-Id} header is now IGNORED, not merely deprecated.</b> A header
 * fallback would be an authentication bypass — anyone can set {@code X-User-Id: <victim>} and read
 * their payments — so the resolver never reads it, and the gateway additionally strips any inbound
 * {@code X-User-Id} before routing. A request carrying only the header and no token
 * never reaches this code: the filter chain rejects it with {@code 401 PAYMENT_UNAUTHORIZED}
 * first; the 401 thrown <em>here</em> is the backstop for an authenticated context that carries no
 * JWT identity.
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
        // with a coded 401 is the honest answer — never a silent null that would leak rows across
        // users, and never the spoofable header this class replaced.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject.trim();
            }
        }
        throw new PaymentException(PaymentErrorCode.PAYMENT_UNAUTHORIZED,
                "No authenticated user for this request — send a valid Bearer token.");
    }
}
