# Keycloak · Identity Architecture

**Type:** high-level (architecture) · **Scope:** the whole system — every service's relationship to Keycloak
**Files:** `architecture-keycloak-identity.html` (source) · `.svg`

## What it shows

How every ScreenMaster service relates to Keycloak, and what travels between them. Read left to
right: a client logs in through Keycloak once, then spends the resulting user JWT at the gateway —
which validates it and proxies the header byte-identical downstream, where each service validates
the *same* token *again*. That repetition is zero-trust, not redundancy, and the diagram draws it
so you can see it: one blue chain across the top, one shared JWKS check on the right. Two more
paths complete the picture — Payment relaying the user's token into Booking (same principal, second
hop), and Notification authenticating as itself when there is no user at all.

## The three token paths

### 1. User JWT, validated at every hop (solid blue)

The client gets a token from Keycloak (`GET TOKEN`) and sends `Authorization: Bearer …` to the
gateway (`BEARER JWT`). The gateway — an OAuth2 resource server on port 8080 — validates the
signature against Keycloak's JWKS, plus expiry and issuer, and rejects garbage before it touches
the cluster. That is the fail-fast half.

Then it proxies the header **byte-identical** downstream (`SAME HEADER`, drawn into Payment and
Notification; Catalog and Booking receive the identical treatment — the budget allowed two proxy
arrows, the rule covers all four). There is deliberately **no TokenRelay filter** on the gateway:
it forwards request headers as-is, so propagation *is* the relay at the edge. The `TokenRelay`
filter belongs to the BFF flow where the gateway *holds* the token; here the client holds it.

Each service then validates the same token **again** against the same JWKS (`JWKS ×5` — gateway,
catalog, booking, payment, notification). A misconfigured route, or a caller already inside the
network, must not mean open services. If the double check looks redundant, that feeling is the
lesson: the edge check is about failing fast, the service check is about never trusting the
network.

**When this is correct:** any request made while the user is waiting — the whole synchronous
read/write surface. **When it is NOT:** background threads (path 3) and queue messages (no user
JWT in a queue message, ever — see the traps).

### 2. User-token relay, service to service (solid blue, `USER RELAY`)

Inside the user's own `POST /payments`, Payment calls Booking's
`GET /bookings/{id}/payability` and re-attaches *the user's* token — rebuilt from the verified
token value by `UserTokenRelayInterceptor`, not copied from the inbound header, and only for a
`JwtAuthenticationToken`. Booking therefore sees the real principal, and its ownership answer is a
genuine authorization check.

The alternative — Payment asserting a `userId` under its own credential — is the
**confused-deputy** problem: a bug in Payment becomes a data leak in Booking. Relay the token and
Booking needs no trust in Payment's claims; it trusts Keycloak's signature, as always.

**When this is correct:** a downstream call made *on behalf of the user, inside the user's
request*. **When it is NOT:** any context without a live user (path 3), or any hop where the
downstream decision should reflect the *service's* authority rather than the user's.

### 3. Machine token, client credentials (dashed)

Notification runs on background threads — a `@RabbitListener` consuming `BookingConfirmed`
seconds after the user's request died, a `@Scheduled` sweep — where there is no user and no
`SecurityContext`. So it authenticates to Keycloak **as itself** (`MACHINE TOKEN`): client id
`notification-svc` plus secret, grant `client_credentials`, via the framework's
`OAuth2AuthorizedClientManager` (registration id `notification-keycloak`). No hand-rolled token
cache — the manager fetches and refreshes before expiry, and a naive cache would race the refresh
window under concurrent listener threads.

The token it gets holds the least-privilege `view-users` role on `realm-management` — enough for
the email lookup by `sub`, not realm admin. Machine identity gets the smallest scope that does the
job, same as user identity.

**When this is correct:** scheduled or event-driven work with no waiting user. **When it is NOT:**
inside a user request (that would *downgrade* the caller's identity to the service's — the mirror
image of confused deputy), or anywhere a user JWT is available and the downstream check is about
the user.

## The realm

Realm `cinema`, imported automatically from `keycloak/realms/cinema-realm.json` by compose
(`--import-realm`), issuer `http://keycloak:8180/realms/cinema`, own Postgres (`keycloak-db` —
Keycloak's tables are its private store, never joined from a service).

The Keycloak node draws the realm's **four clients** as a strip inside the box — client id on the
left, its one allowed grant on the right. (The `public`/`confidential` split is *not* in the strip:
at font-size 8 in Geist Mono, `notification-svc` + `confidential · client creds` overruns the row by
24px, and widening the node far enough for both columns would push it out of the IDENTITY zone. The
grant is the discriminating fact anyway — `code+PKCE` and `direct grant` are public-client grants,
`client creds` is a confidential one — so the strip keeps the grant and the prose below keeps the
rest.) That is what makes the focal
node informative rather than decorative: every arrow touching Keycloak now lands on a *named*
client. `GET TOKEN` arrives at `cinema-web` (or `cinema-dev-cli` from `get-token.sh`);
`MACHINE TOKEN` arrives at `notification-svc`. Without the strip the three token paths converge on
an anonymous orange rectangle and the reader has to take the realm's shape on trust.

- Roles `USER` (a **default** role, so self-registration yields a user who can book immediately)
  and `ADMIN`.
- Clients (the strip drawn inside the node): `cinema-web` — public, Authorization Code + PKCE, the real user-facing client;
  `cinema-dev-cli` — public, Direct Access Grant **only**, for `scripts/get-token.sh`. It is kept
  off the real client on purpose: the grant a script needs (password in, token out) is exactly the
  grant a browser client must never offer, so they are two clients rather than one client with two
  personalities. `payment-svc` / `notification-svc` — confidential, client-credentials;
  `notification-svc`'s service account holds `view-users`, nothing more.
- Seeded users `alice` (`USER`) and `admin` (`USER` + `ADMIN`), password `password` — dev only,
  like every secret in the realm JSON; services read theirs from env.

## The traps

- **`realm_access` vs `scope`.** Spring's default converter reads the `scope` claim and emits
  `SCOPE_…`. Keycloak puts this system's roles in **`realm_access.roles`**. With the default,
  every `hasRole('ADMIN')` silently evaluates false — no exception, no log line. Each service
  carries its own `KeycloakRealmRoleConverter` mapping `realm_access.roles` → `ROLE_…` (drawn as
  the note under the services zone, tied by a hairline leader to the `JWKS ×5` edge — role mapping
  is what each service does *after* that signature check, so the note is an annotation **on** that
  edge, not a free-floating caption). The duplication is deliberate — the repo draws a
  hard no-shared-jar boundary between services.
- **Lazy JWKS vs eager issuer discovery.** The gateway builds its decoder from
  `issuer + "/protocol/openid-connect/certs"` with `createDefaultWithIssuer(issuer)` — first
  network call on first request, never at boot. Boot's default issuer-uri discovery fetches OIDC
  metadata eagerly at bean creation, which would make the gateway unbootable whenever Keycloak is
  briefly down. One URI feeds both the JWKS source and the accepted `iss`, so they cannot drift.
- **The one-issuer rule (`KC_HOSTNAME`).** Keycloak mints exactly
  `iss=http://keycloak:8180/realms/cinema`; every service pins that same URI. Host callers reach
  `localhost:8180`, container callers `keycloak:8180`, and the minted `iss` matches what the
  validators expect — because there is only one name that counts.
- **The stripped `X-User-Id`.** The gateway removes `X-User-Id` on every API route. It was the
  pre-Keycloak identity mechanism; no downstream code may ever treat a client-set header as
  identity again.
- **No user JWT in a queue message.** The queue is a durable log — a credential at rest — and the
  token would be expired by consume time anyway. Events carry the `userId` as opaque data;
  Notification resolves the email with its own machine token.
- **Coarse at the edge, fine at the method.** `anyRequest().authenticated()` on the gateway and
  services; `hasRole('ADMIN')` via `@PreAuthorize` next to the thing it protects (Booking's
  `TheaterController` / `ShowtimeController` gate every inventory and showtime write). Errors
  render as RFC 9457 `ProblemDetail` with a machine-readable `code` — never an opaque empty 401.

## Icons

| Icon | Where | Style | Source |
|---|---|---|---|
| Keycloak badge + keyhole | Keycloak node, top-right, legend | stroked | **custom, hand-drawn** |
| user | Client node, top-right | stroked | **custom** |
| gateway arch | Gateway node, top-right | stroked | **custom** |
| datastore cylinder | `keycloak-db`, top-right | stroked | **custom** |

The Keycloak mark is a **simplification in the house style, not the official asset**: a hexagonal
badge outline with an inner keyhole (circle + stem), drawn to read at 20px. It follows the same
idiom as the booking diagram's custom glyphs — 24×24 paths, `fill="none"`, 1.5px round-cap
strokes — with one deliberate deviation: the paths are **inlined as translated `<g>` groups at
their placement**, not `<symbol>` + `<use>`. This checkout's rasterizer (old librsvg via
ImageMagick) renders `<use>` → `<symbol>` as nothing and ignores `currentColor` entirely —
verified with minimal repros before inlining — so the house idiom would have left the Keycloak
corner empty in every raster export. Each glyph is placed exactly once, so inlining duplicates
nothing; to recolour one, change the `stroke` on its `<g>`.

## Deliberately out of scope

The 9-node budget forced choices. Folded into sublabels: the per-service resource-server shape,
`@PreAuthorize` gating, HMAC-authenticated payment webhooks (`POST /api/payments/webhooks/**`
bypasses JWT at the gateway and at Payment — the one public route that is never a JWT), the
`dev-cli` grant, and RabbitMQ (no JWT crosses it — a deliberate absence, stated in prose instead
of drawn). Cut entirely: Eureka/`lb://` resolution, the `SecurityProblemSupport` plumbing, the
`get-token.sh` flow internals, and Payment's own `payment-svc` machine client — registered, not yet spent, and now visible in the
client strip even though no arrow leaves it (a provisioned identity with no traffic is worth seeing
as exactly that). The sidecar carries all of them; the picture carries the three paths.

## Regenerating

The `.svg` is extracted from the HTML: take the first `<svg>` node, insert a Google Fonts
`@import` in a `<style>`, and XML-escape bare `&`. **Child order matters** — `<title>` must stay
the first child of `<svg>`, so the `<style>` goes *after* `<title>` and `<desc>`, not immediately
after the opening tag:

```bash
python3 - <<'EOF'
import re
html = open('docs/diagrams/architecture-keycloak-identity.html', encoding='utf-8').read()
svg = re.search(r'<svg[\s\S]*?</svg>', html).group(0)
imp = ('<style>@import url("https://fonts.googleapis.com/css2?'
       'family=Instrument+Serif:ital@0;1&amp;family=Geist:wght@400;500;600&amp;'
       'family=Geist+Mono:wght@400;500;600&amp;display=swap");</style>')
# insert AFTER </desc> so <title> remains the first child
svg = svg.replace('</desc>', '</desc>' + imp, 1)
open('docs/diagrams/architecture-keycloak-identity.svg', 'w', encoding='utf-8').write(
    '<?xml version="1.0" encoding="UTF-8"?>\n' + svg)
EOF
```

To rasterize a `.png` (this checkout **does** have ImageMagick, though its librsvg backend is the
reason the icons are inlined rather than `<symbol>` + `<use>` — see Icons):

```bash
convert -density 150 -background "#f5f5f5" \
  docs/diagrams/architecture-keycloak-identity.svg \
  docs/diagrams/architecture-keycloak-identity.png
```

No `.png` is committed — the `.svg` is the committed render, as with the other diagrams here. The
`.html` and `.svg` render correctly in any modern browser as-is.
