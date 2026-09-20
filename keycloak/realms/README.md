# ScreenMaster realm `cinema` — imported automatically by `docker compose up` (no console clicking).

The keycloak service mounts this directory at `/opt/keycloak/data/import` and starts with
`--import-realm`, so the realm comes up correctly from compose alone.

## What the realm contains

- Roles `USER` (customer) and `ADMIN` (operator). `USER` is a *default* role, so Keycloak's
  self-registration (enabled — that IS the signup feature, no custom code) yields a user who can
  immediately book.
- Clients:
  - `cinema-web` — public, Authorization Code + PKCE (the real user-facing client).
  - `cinema-dev-cli` — public, Direct Access Grant ONLY, so `scripts/get-token.sh` gets a token
    in one line for curl/scripts. Kept off the real client on purpose.
  - `payment-svc` / `notification-svc` — confidential, client-credentials (service accounts).
- Seeded users (password `password`, real email attribute set):
  - `alice` → role `USER` → `alice@screenmaster.local`
  - `admin` → roles `USER` + `ADMIN` → `admin@screenmaster.local`
- The `notification-svc` service account holds the `view-users` role on the built-in
  `realm-management` client — LEAST PRIVILEGE for part 2's email lookup, not a realm admin.

## DEV SECRETS ONLY

Every secret in `cinema-realm.json` (`payment-svc-dev-secret`, `notification-svc-dev-secret`,
the seeded passwords) is a well-known dev value. Production mints its own realm with its own
secrets; services read theirs from env (`NOTIFICATION_SVC_SECRET`, …), never from this file.
