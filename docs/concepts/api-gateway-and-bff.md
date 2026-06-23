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
This project's `pom.xml` already includes `spring-cloud-starter-gateway-server-webmvc`. Routes look like:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: catalog
          uri: lb://catalog          # lb:// = resolve via Eureka discovery + load balance
          predicates: [ "Path=/api/movies/**" ]
        - id: booking
          uri: lb://booking
          predicates: [ "Path=/api/bookings/**" ]
        - id: payment
          uri: lb://payment
          predicates: [ "Path=/api/payments/**" ]
```
- **`predicates`** decide *which* requests match a route (path, method, header, …).
- **`filters`** (not shown) transform the request/response (add headers, strip the auth header, rate-limit).
- **`lb://name`** means "look this name up in Eureka and load-balance across its instances" — the discovery integration. (`http://host:port` works too for a fixed address in Compose.)

## How we use it here (field guide M1, M7)
- M1: the gateway routes `/api/...` paths to catalog, booking, payment.
- M7: the gateway becomes a **resource server** — it validates the Keycloak JWT at the edge; `curl` without a token → `401` *before* it reaches any service.

## BFF
If web and mobile need different response shapes, give each its own gateway (a BFF) so neither gets a lowest-common-denominator API. We don't build a BFF in this lab — name-drop level is enough.

## Interview lens
"One front door for routing, edge auth, and rate limiting — implemented once instead of per service. A BFF is the per-client-type variant. Internal calls bypass the gateway and go direct via discovery; routing internal traffic through the edge is an anti-pattern."
