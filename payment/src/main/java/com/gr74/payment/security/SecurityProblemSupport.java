package com.gr74.payment.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gr74.payment.exception.PaymentErrorCode;

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
 * container's default: an empty 401/403 with no body. A client branching on {@code code} (the
 * documented contract — e.g. distinguishing "log in again" from "ask an admin") would see the
 * field simply missing. This class closes that hole by implementing both filter-chain hooks:
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} — no (or invalid) token → {@code 401
 *       PAYMENT_UNAUTHORIZED}.</li>
 *   <li>{@link AccessDeniedHandler} — valid token, insufficient role (a {@code USER} calling the
 *       {@code ADMIN}-only refund endpoint, including a {@code @PreAuthorize} denial) → {@code 403
 *       PAYMENT_ACCESS_DENIED}.</li>
 * </ul>
 *
 * <p><b>Why a separate 403 code from {@code PAYMENT_FORBIDDEN}:</b> that code already means "this
 * payment belongs to another user" (the ownership guard in {@code PaymentService}). A role denial
 * is a different fact — "you are authenticated but not an admin" — and conflating the two would
 * teach clients the wrong retry (re-logging in as the same user can never fix a missing role).
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
        write(response, HttpStatus.UNAUTHORIZED, PaymentErrorCode.PAYMENT_UNAUTHORIZED,
                "Authentication required — send a valid Bearer token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Forbidden request to {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
        write(response, HttpStatus.FORBIDDEN, PaymentErrorCode.PAYMENT_ACCESS_DENIED,
                "Insufficient permissions for this resource");
    }

    /**
     * Writes the RFC 9457 body with the same field shape {@code GlobalExceptionHandler} produces
     * ({@code type/title/status/detail} plus the flat custom {@code code}), so callers branch on
     * one contract no matter which layer rejected them.
     */
    private void write(HttpServletResponse response, HttpStatus status, PaymentErrorCode code,
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
