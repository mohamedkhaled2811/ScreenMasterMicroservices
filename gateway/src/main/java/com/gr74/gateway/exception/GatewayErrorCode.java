package com.gr74.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for the gateway's edge.
 *
 * <p>The gateway owns no business controllers, so these constants travel only through the security
 * filter chain (rendered by {@code SecurityProblemSupport} — there is deliberately no
 * {@code @RestControllerAdvice}, nothing would use it). They keep the repo's standing convention
 * ("errors carry a machine-readable code") honest at the front door: a client rejected at the edge
 * gets the same RFC 9457 shape with the same flat {@code code} it would get from any service
 * behind the gateway.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart.
 */
public enum GatewayErrorCode {

    /**
     * No (or an invalid) Bearer token at the edge. Kept distinct from {@link #GATEWAY_FORBIDDEN}
     * on purpose: "log in" and "ask an admin" are different answers.
     */
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
