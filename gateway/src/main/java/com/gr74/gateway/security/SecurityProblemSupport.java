package com.gr74.gateway.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gr74.gateway.exception.GatewayErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders edge 401/403 failures as RFC 9457 {@code ProblemDetail} with the same {@code code} shape.
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        log.warn("Unauthenticated request to {}: {}", request.getRequestURI(), authException.getMessage());
        write(response, HttpStatus.UNAUTHORIZED, GatewayErrorCode.GATEWAY_UNAUTHORIZED,
                "Authentication required — send a valid Bearer token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Forbidden request to {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
        write(response, HttpStatus.FORBIDDEN, GatewayErrorCode.GATEWAY_FORBIDDEN,
                "Insufficient permissions for this resource");
    }

    /** Writes the RFC 9457 body with the flat {@code code} property. */
    private void write(HttpServletResponse response, HttpStatus status, GatewayErrorCode code,
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
