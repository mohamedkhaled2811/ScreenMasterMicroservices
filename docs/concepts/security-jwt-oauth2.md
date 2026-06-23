# Security: JWT, OAuth2/OIDC, zero trust, Keycloak

Two distinct problems interviewers love to see kept apart:
1. authenticating **users at the edge**, and
2. authenticating **services to each other** inside.

## Edge: OAuth2/OIDC + JWT
The client signs in against the identity provider — **Keycloak** in this stack — via the OAuth2 **authorization code flow (with PKCE)**. OIDC layers identity on top, returning an **ID token** alongside the **access token** (a JWT). The client sends `Authorization: Bearer <jwt>`; the **gateway validates the JWT first** — signature against Keycloak's published public keys (the **JWKS** endpoint), plus expiry, issuer, and audience — rejecting garbage before it touches the cluster.

**Why JWT for microservices:** validation is **stateless and local**. Any service verifies the signature with cached public keys — no per-request call to a session store or to Keycloak. The token carries identity + authorization claims (`sub`, roles, scopes).

**The cost — volunteer it:** a JWT **cannot be revoked** — it's valid until `exp`. Mitigation: short-lived access tokens (5–15 min) + refresh tokens that *can* be revoked at Keycloak. The alternative — **opaque tokens** checked via the **introspection endpoint** — gives instant revocation at the price of a network hop per request. Knowing the dial exists is the point.

## Inside: zero trust between services
Past the gateway, do services just trust each other? **No — zero trust.** The internal network is *not* a security boundary; one compromised pod shouldn't get the run of the cluster. So each downstream service is itself a **resource server** that validates the JWT again and enforces its own authorization from claims.

In Spring this is `spring-boot-starter-oauth2-resource-server` + the issuer URI:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8085/realms/cinema
```
**Never trust spoofable identity headers** like `X-User-Id` from outside; the gateway strips and re-sets such headers if used at all.

## Service-as-actor: client credentials
For calls where *the service itself* is the actor — the outbox relay, a nightly job, Notification fetching poster URLs — there is no user JWT. Use the OAuth2 **client credentials grant**: the service authenticates to Keycloak with its own client id/secret and gets a machine token scoped to what it may do.
```yaml
spring.security.oauth2.client.registration.notification-m2m:
  client-id: notification-service
  client-secret: ${KC_SECRET}        # from env/secret store, NEVER git
  authorization-grant-type: client_credentials
  scope: catalog.read
```

## User-context fan-out: token relay
When a user's request fans out (Booking calls Payment *on behalf of* user 42), the simple approach is **token relay** — forward the user's JWT so Payment sees the real principal. Limits to name: the token may expire mid-flow (especially across async boundaries — **never put a user JWT into a queue message**; store the user *id* in the event and act with a machine token), and it grants the callee everything the user could do. The refined tool is **OAuth2 Token Exchange (RFC 8693)** — trade the user token for a narrower one per hop (name-drop level).

## Transport: mTLS and the mesh
JWTs authenticate the *request*; **mutual TLS** authenticates and encrypts the *connection* — both sides present certificates, so a rogue process can't even open a socket to Payment, and traffic can't be sniffed in-cluster. Managing per-service certs by hand is misery — that's the real selling point of a **service mesh** (Istio, Linkerd): a sidecar proxy beside each pod transparently upgrades all traffic to mTLS and handles retries/timeouts/telemetry without touching app code. (Name-drop; we don't run a mesh in this lab.)

## How we use it here (field guide M7)
Add a Keycloak container (realm `cinema`, a public client for users, a `booking-service` client with client-credentials). Gateway + every service become resource servers validating the JWT. Booking calls Payment with a relayed token; the outbox relay uses its own client-credentials token. Demo: no token → 401 at the gateway; user token → booking succeeds; **payment called directly with no token → 401 too** (zero trust, not just perimeter).

This replaces the monolith's hard-coded HMAC JWT secret — we move to Keycloak-issued **RS256** (asymmetric: Keycloak signs with a private key, services verify with the public key, no shared secret).

## The trap: "the gateway checks the token, so internal services can skip auth"
That's perimeter / castle-and-moat thinking. Pushback: **defense in depth** — every service validates the JWT (cheap and local), service identities use client-credentials tokens, ideally mTLS underneath. One SSRF or one compromised pod shouldn't equal total compromise.
