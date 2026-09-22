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
 * Renders 401/403 filter-chain rejections as RFC 9457 ProblemDetails with a stable {@code code}.
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

    /** Writes the RFC 9457 body in the same shape {@code GlobalExceptionHandler} produces. */
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
