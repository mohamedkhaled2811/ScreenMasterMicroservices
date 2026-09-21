# Keycloak clients & client scopes — what they are, and ours specifically

> **Why this file:** The realm defines four clients and a wall of scopes in the Keycloak admin
> console. This explains what a *client* is, how a user actually reaches `cinema-web`, and
> what *client scopes* do — with claims decoded from real tokens minted by this realm.
>
> Companion to [security-jwt-oauth2.md](security-jwt-oauth2.md) (the protocol theory) and
> [current-user-resolution.md](current-user-resolution.md) (how `sub` becomes `@CurrentUser`).

---

## 1. A "client" is an application, not a user

This trips up almost everyone at first. In OIDC:

- a **user** (Keycloak calls it a *user*) is a person — `alice`, `admin`
- a **client** is *an application that asks Keycloak for a token*

`alice` logging in through a browser app and `alice` logging in through curl are the **same
user** arriving via **two different clients**. The client decides *how* the asking is
allowed to happen, not *who* is asking.

Each client is locked to specific **flows** (ways of getting a token). That is the whole
point: a browser app and a background service have completely different threat models, so
they get different rules. Collapsing them into one client is how you end up with a browser
app that can do client-credentials, which is a security hole.

---

## 2. Our four clients

These live in [`keycloak/realms/cinema-realm.json`](../../keycloak/realms/cinema-realm.json)
— committed and reviewed, not clicked into existence.

| Client | Public/Confidential | Flow enabled | Who uses it |
|---|---|---|---|
| `cinema-web` | public | Authorization Code + PKCE | the real user-facing app (browser) |
| `cinema-dev-cli` | public | Direct Access Grant (password) | `get-token.sh`, curl, Postman |
| `payment-svc` | confidential (secret) | client credentials | Payment, acting as itself |
| `notification-svc` | confidential (secret) | client credentials | Notification, acting as itself |

### 2.1 `cinema-web` — the user-facing client

```json
"publicClient": true,
"standardFlowEnabled": true,
"directAccessGrantsEnabled": false,
"attributes": { "pkce.code.challenge.method": "S256" },
"redirectUris": ["http://localhost:8080/*"],
"webOrigins": ["http://localhost:8080"]
```

- **public** = ships no secret. Correct for anything running in a browser: a secret in
  JavaScript is readable by the user, so it is not a secret. PKCE replaces it.
- **PKCE (S256)** = the client generates a random `code_verifier`, sends its SHA-256 hash
  up front, and must present the original when redeeming the auth code. A stolen code is
  useless without the verifier, which never leaves the browser.
- **`directAccessGrantsEnabled: false`** — deliberate. A browser app must **never** handle a
  raw password; it redirects to Keycloak's own login page instead.
- **`redirectUris`** is an allowlist. Without it, an attacker could ask Keycloak to send
  *your* token to *their* site. Keycloak refuses any redirect target not on this list.

### 2.2 How a user actually opens `cinema-web` — and why you can't today

**You can't open it yet, because there is no frontend.** `cinema-web` is a *registration*
for an app that has not been built yet. Its `redirectUris` point at
`http://localhost:8080/*` — the gateway — which currently serves the API and returns `401`
at `/`, not a web page.

That is not a bug or an oversight; a browser SPA was explicitly out of scope. The client
exists so the realm models the real design, and so the user-facing flow is configured
correctly the day a frontend lands.

**When a frontend does exist, the flow a user experiences is:**

```
1. user clicks "Log in" in the SPA
2. browser -> Keycloak:  /realms/cinema/protocol/openid-connect/auth
                         ?client_id=cinema-web&response_type=code
                         &redirect_uri=http://localhost:8080/callback
                         &code_challenge=<sha256(verifier)>&code_challenge_method=S256
3. Keycloak shows ITS OWN login page   <- the password is typed here, never in our app
4. Keycloak redirects back:  http://localhost:8080/callback?code=<auth code>
5. SPA exchanges code + code_verifier for an access token (no secret involved)
6. SPA calls our API with `Authorization: Bearer <token>`
```

Step 3 is the security payoff: **our code never sees the password.** Compare
`cinema-dev-cli` below, which does — and is therefore dev-only.

**To try the flow today without a frontend**, paste this in a browser (it will authenticate
and then fail at the redirect, because nothing serves `/callback` — you will still see the
Keycloak login page and a `?code=` in the address bar, which is the part worth seeing):

```
http://keycloak:8180/realms/cinema/protocol/openid-connect/auth?client_id=cinema-web&response_type=code&scope=openid%20email%20profile&redirect_uri=http://localhost:8080/callback
```

> Use the `keycloak:8180` hostname, not `localhost:8180` — see §5.

### 2.3 `cinema-dev-cli` — the convenience client

`directAccessGrantsEnabled: true` and **nothing else**. Username + password in, token out,
one curl call. This is what `scripts/get-token.sh` and the Postman "A Auth" folder use.

**This flow is deprecated in OAuth 2.1** precisely because the application handles the
user's password. It is fine for a local CLI against a throwaway realm; it must never be how
a real app logs users in. Keeping it as a *separate client* (rather than a flag on
`cinema-web`) means the browser client structurally cannot use it.

### 2.4 `payment-svc` / `notification-svc` — machine clients

`serviceAccountsEnabled: true`, confidential, every user-facing flow off. These get a token
via **client credentials**: no user involved, the service authenticates as *itself*.

This is the direct answer to the project constraint *"don't assume I will always make the
services use the user token."* The rule we follow:

> Relay the **user's token** when the call is made on behalf of the user, in the user's
> request. Use a **machine token** when the service is acting as itself — background jobs,
> event consumers, schedulers.

Notification consuming a `BookingConfirmed` event has **no user request to borrow a token
from**, so it authenticates as itself. `notification-svc` is granted `view-users` only —
least privilege, exactly enough to look up a booking user's email address.

---

## 3. The clients you did *not* create

Every realm ships with these. The give-away is the placeholder name (`client_account`).

| Client | What it is |
|---|---|
| `account` / `account-console` | user self-service page — profile, password, sessions |
| `security-admin-console` | **the admin dashboard you are looking at** |
| `admin-cli` | the `kcadm` CLI tool |
| `realm-management` | not an app — it *holds* admin roles (like `view-users`) that other clients get granted |
| `broker` | identity brokering (log in via Google/GitHub); unused here |

Leave them alone. `realm-management` matters indirectly: it is where
`notification-svc`'s `view-users` permission comes from.

---

## 4. Client scopes

### 4.1 What a scope actually does

A **client scope** is a named bundle of *protocol mappers*, and a mapper is a rule that puts
a **claim** into the token. So:

```
scope  ->  bundle of mappers  ->  claims in the JWT  ->  what our services can read
```

`email` is not a magic word Keycloak understands — it is a scope containing a mapper that
copies the user's email into an `email` claim. Drop the scope, lose the claim.

### 4.2 Default vs optional

- **Default** scopes are granted **always**, whether or not anyone asks.
- **Optional** scopes are granted **only when explicitly requested** via `scope=...`.

All four of our clients carry the same set (Keycloak's stock assignment):

```
default:  web-origins, acr, roles, profile, basic, email
optional: address, phone, offline_access, organization, microprofile-jwt
```

Proven against this realm — `phone` is optional, so it is absent until requested:

```bash
# no scope requested
scope: profile email                | phone_number: ABSENT

# scope=openid phone requested
scope: openid phone profile email   | phone_number: (granted; alice has no phone value set)
```

### 4.3 The scopes that matter to *our* code

Decoded from a real `alice` token minted by this realm:

| Claim | Value | Comes from | Who reads it |
|---|---|---|---|
| `iss` | `http://keycloak:8180/realms/cinema` | the realm | every service's `JwtDecoder` — must match exactly |
| `sub` | `8f86b77e-…` | `basic` | **`@CurrentUser`** → `bookings.user_id` |
| `email` | `alice@screenmaster.local` | **`email`** | Notification |
| `preferred_username` | `alice` | `profile` | logs / display only |
| `realm_access.roles` | `["USER"]` | **`roles`** | **`KeycloakRealmRoleConverter`** → `hasRole("ADMIN")` |

Two of these are load-bearing and worth remembering:

1. **`roles`** puts roles in `realm_access.roles`. Spring Security's *default* converter
   reads the `scope` claim instead, so `hasRole("ADMIN")` silently fails until you install a
   custom converter. That is why every service has a `KeycloakRealmRoleConverter`.
2. **`email`** is what unblocks notifications. Previously there was no email address
   anywhere in the system. Keycloak is now the user store that supplies it.

Access tokens are short-lived here — **`expires_in: 300`** (5 minutes). If a Postman request
starts 401-ing mid-session, re-run the token request; it is expiry, not a broken config.

---

## 5. The one hostname gotcha

The realm's issuer is `http://keycloak:8180`, and that single string serves two audiences
that resolve names differently:

| name | your browser | containers |
|---|---|---|
| `keycloak` | needs an `/etc/hosts` entry | ✅ Docker DNS |
| `localhost` | ✅ | ❌ resolves to the container itself |

Because the issuer is baked into every OIDC URL Keycloak hands out — including the login
redirect — the admin console cannot log in from a browser that can't resolve `keycloak`.
Local setup therefore needs one line:

```bash
sudo sh -c 'echo "127.0.0.1 keycloak" >> /etc/hosts'
```

Then use **`http://keycloak:8180`** in the browser so the address bar and the OIDC redirects
agree. (Mixing `localhost` and `keycloak` breaks the flow's cookies and redirect-URI checks.)

Keeping one issuer string is deliberate: each service derives both its JWKS URL *and* its
accepted `iss` from the same property, which makes an issuer mismatch structurally
impossible rather than a thing you debug at 2am.

---

## 6. Try it yourself

```bash
# a user token (USER role)
./scripts/get-token.sh alice password

# decode it — see sub, email, realm_access.roles
./scripts/get-token.sh alice password | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool

# an admin token (USER + ADMIN)
./scripts/get-token.sh admin password
```

> **Two different `admin`s.** The console login `admin`/`admin` lives in the **master**
> realm. The app user `admin`/`password` lives in the **cinema** realm. Different realms,
> unrelated accounts.
