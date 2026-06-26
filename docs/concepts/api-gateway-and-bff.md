# API gateway (and BFF)

## What it is
The **API gateway** is the single front door for external clients. Routing, TLS termination, edge authentication, rate limiting, and response shaping live there — **once**, instead of in every service. A **BFF** (backend-for-frontend) is a gateway variant: one tailored edge API per client type (mobile vs web).

## Why it exists
Without a gateway, every client must know all N service addresses, and every service must re-implement auth, rate limiting, and CORS. The gateway centralizes the cross-cutting edge concerns and gives clients one stable address.

## What lives at the gateway
- **Routing** — map external paths to internal services (`/api/movies/**` → catalog).
- **Edge authentication** — validate the JWT *first*, reject garbage before it touches the cluster (see [security-jwt-oauth2.md](security-jwt-oauth2.md)).
- **Rate limiting / load shedding** — protect the cluster from clients.
- **TLS termination**, **CORS**, **response shaping**.

## What does NOT go through the gateway
**Internal service-to-service calls.** Booking → Payment goes *direct* (via [discovery](service-discovery.md)) or through a service mesh — not back out through the public edge. Sending internal traffic through the gateway adds a hop and a single point of failure for traffic that's already inside the trust boundary.

## Spring Cloud Gateway — the routes (this project uses it)
This project's `gateway/pom.xml` includes `spring-cloud-starter-gateway-server-webmvc`. In the
web-mvc flavour the routes live under `spring.cloud.gateway.server.webmvc.routes` (the reactive
flavour drops the `server.webmvc`). Our actual `gateway/src/main/resources/application.yml`:
```yaml
spring:
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: catalog
              uri: lb://catalog                 # lb:// = resolve via Eureka discovery + load balance
              predicates: [ "Path=/api/movies/**" ]
              filters:    [ "StripPrefix=1" ]    # /api/movies/7 -> /movies/7
            - id: booking
              uri: lb://booking
              predicates: [ "Path=/api/bookings/**" ]
              filters:    [ "StripPrefix=1" ]
            - id: payment
              uri: lb://payment
              predicates: [ "Path=/api/payments/**" ]
              filters:    [ "StripPrefix=1" ]    # /api/payments -> /payments (PaymentController's path)
```
- **`predicates`** decide *which* requests match a route (path, method, header, …).
- **`filters`** transform the request/response on the way through. We use **`StripPrefix=1`** so the
  `/api` namespace is an *edge-only* concern: the gateway drops the first segment and forwards the
  rest, letting each service keep bare paths (`/payments`, `/movies`). Other filters add headers,
  strip the auth header, or rate-limit (Phase 7).
- **`lb://name`** means "look this name up in Eureka and load-balance across its instances" — the discovery integration. (`http://host:port` works too for a fixed address in Compose.)
- **Unmatched paths** (no predicate matches) get a plain **404**; a matched route to a service with
  no healthy instance gets a **503** from the load balancer — a useful way to tell "no such route"
  from "route exists, backend down" when debugging.

## How we use it here (field guide M1, M7)
- M1: the gateway routes `/api/...` paths to catalog, booking, payment.
- M7: the gateway becomes a **resource server** — it validates the Keycloak JWT at the edge; `curl` without a token → `401` *before* it reaches any service.

## BFF
If web and mobile need different response shapes, give each its own gateway (a BFF) so neither gets a lowest-common-denominator API. We don't build a BFF in this lab — name-drop level is enough.

## Interview lens
"One front door for routing, edge auth, and rate limiting — implemented once instead of per service. A BFF is the per-client-type variant. Internal calls bypass the gateway and go direct via discovery; routing internal traffic through the edge is an anti-pattern."
