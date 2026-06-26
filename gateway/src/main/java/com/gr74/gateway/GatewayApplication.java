package com.gr74.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API gateway — the single public front door for the whole system.
 *
 * <p>Built on <b>Spring Cloud Gateway (web-mvc)</b>: it owns no data (no JPA, no DB), it only routes.
 * External clients learn one address (port 8080) instead of every service's host; the cross-cutting
 * edge concerns live here once rather than in every service. See {@code docs/concepts/api-gateway-and-bff.md}.
 *
 * <p>A Eureka <b>client</b> (the starter auto-registers on startup; no enable-annotation needed) so the
 * route URIs can use {@code lb://catalog}, {@code lb://booking}, {@code lb://payment} — "resolve this
 * name via the registry and load-balance across its healthy instances." The routes themselves are
 * declared in {@code application.yml}; this class is wiring only.
 *
 * <p>Scope for BUILD_PLAN 1.3 is routing only. Edge authentication (the gateway becomes a resource
 * server) and rate limiting arrive in Phase 7 (M7).
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
