package com.gr74.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes for the gateway edge. Each carries the HTTP status it maps to.
 */
public enum GatewayErrorCode {

    /** No (or an invalid) Bearer token at the edge. */
    GATEWAY_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /** A valid token without the role the route needs. */
    GATEWAY_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied");

    private final HttpStatus status;
    private final String title;

    GatewayErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** Short, human-readable summary used as the {@code ProblemDetail} title. */
    public String title() {
        return title;
    }
}
