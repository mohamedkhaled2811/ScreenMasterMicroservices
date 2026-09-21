package com.gr74.booking.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gr74.booking.exception.BookingErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders authentication (401) and authorization (403) failures as the same RFC 9457
 * {@code ProblemDetail} shape the controllers produce — with the same machine-readable
 * {@code code} property.
 *
 * <p><b>Why the filter chain needs its own renderer:</b> {@code GlobalExceptionHandler} is a
 * {@code @RestControllerAdvice} — it only sees exceptions thrown <em>inside</em> a controller.
 * Security rejections happen in the servlet filter chain <em>before</em> any controller runs (no
 * token, expired token, valid token without the role), so without this class they fall back to the
 * container's default: an empty 401/403 with no body. A sibling service (or a script) branching on
 * {@code code} would see the field simply missing — the exact drift
 * {@code docs/concepts/error-handling-problemdetail.md} forbids. This class closes that hole by
 * implementing both filter-chain hooks in one place:
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} — no (or invalid) token → {@code 401
 *       BOOKING_UNAUTHORIZED}.</li>
 *   <li>{@link AccessDeniedHandler} — valid token, insufficient role (e.g. a {@code USER} calling
 *       an {@code ADMIN} inventory write, including a {@code @PreAuthorize} denial) → {@code 403
 *       BOOKING_FORBIDDEN}.</li>
 * </ul>
 *
 * <p><b>401 vs 403 is a deliberate, demoable distinction</b>:
 * <em>unauthenticated</em> (who are you? — no/invalid token) versus <em>unauthorized</em> (we know
 * who you are and the answer is no). They carry different codes so a client can tell "log in" apart
 * from "ask an admin".
 *
 * <p><b>Why the JSON is built by hand</b> instead of serializing a {@code ProblemDetail}: the
 * {@code code} property only appears flat in the body because Spring registers a Jackson mixin on
 * its HTTP-message converters — a mixin a raw {@code ObjectMapper.writeValue} call does not apply
 * (the code would nest under {@code properties}). Building the five RFC 9457 fields explicitly
 * keeps the body byte-identical to what {@code GlobalExceptionHandler} renders.
 *
 * <p><b>Not a {@code @Component} on purpose:</b> the instance is exposed as a {@code @Bean} from
 * {@code SecurityConfig} instead, so a {@code @WebMvcTest} slice needs only
 * {@code @Import(SecurityConfig.class)} to get the real chain — a slice does not component-scan,
 * and importing two classes in every test would drift. (Exactly one definition exists either way,
 * so the full application context is unaffected.)
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        log.warn("Unauthenticated request to {}: {}", request.getRequestURI(), authException.getMessage());
        write(response, HttpStatus.UNAUTHORIZED, BookingErrorCode.BOOKING_UNAUTHORIZED,
                "Authentication required — send a valid Bearer token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Forbidden request to {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
        write(response, HttpStatus.FORBIDDEN, BookingErrorCode.BOOKING_FORBIDDEN,
                "Insufficient permissions for this resource");
    }

    /**
     * Writes the RFC 9457 body with the same field shape {@code GlobalExceptionHandler} produces
     * ({@code type/title/status/detail} plus the flat custom {@code code}), so callers branch on
     * one contract no matter which layer rejected them.
     */
    private void write(HttpServletResponse response, HttpStatus status, BookingErrorCode code,
            String detail) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "about:blank");
        body.put("title", code.title());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code.name());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
