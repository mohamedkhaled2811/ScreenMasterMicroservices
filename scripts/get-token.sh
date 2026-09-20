#!/usr/bin/env bash
#
# get-token.sh — fetch a Keycloak access token for a seeded user (Phase 7, decision A1/C1).
#
# Uses the Direct Access Grant against the dedicated `cinema-dev-cli` public client (password
# grant), so every demo and script is one line away from a token:
#
#   TOKEN=$(./scripts/get-token.sh alice)          # USER role
#   TOKEN=$(./scripts/get-token.sh admin)          # USER + ADMIN roles
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/bookings/my
#
# The real user-facing client (`cinema-web`) uses Authorization Code + PKCE — the redirect dance
# is correct for browsers and awkward for curl, which is why this separate dev client exists.
# Needs only curl + ONE of jq / python3. Fails loudly (non-zero + stderr) when Keycloak is down
# or the credentials are wrong — never prints an empty token.
#
# Env overrides:
#   KEYCLOAK_URL      base URL of Keycloak        (default http://localhost:8180)
#   KEYCLOAK_REALM    realm                       (default cinema)
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="${KEYCLOAK_REALM:-cinema}"
CLIENT_ID="cinema-dev-cli"
USERNAME="${1:-alice}"
PASSWORD="${2:-password}"

TOKEN_URL="$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token"

resp="$(curl -sS -m 10 -w $'\n%{http_code}' -X POST "$TOKEN_URL" \
  --data-urlencode grant_type=password \
  --data-urlencode client_id="$CLIENT_ID" \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD")" \
  || { echo "ERROR: cannot reach Keycloak at $TOKEN_URL — is 'docker compose up keycloak' running?" >&2; exit 1; }

http_code="${resp##*$'\n'}"
body="${resp%$'\n'*}"

if [[ "$http_code" != 2* ]]; then
  echo "ERROR: Keycloak refused the token request (HTTP $http_code) for user '$USERNAME':" >&2
  echo "$body" >&2
  exit 1
fi

extract_with_jq() { printf '%s' "$body" | jq -r '.access_token // empty' 2>/dev/null; }
extract_with_python() {
  printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token") or "")' 2>/dev/null
}

token=""
if command -v jq >/dev/null 2>&1; then
  token="$(extract_with_jq)"
elif command -v python3 >/dev/null 2>&1; then
  token="$(extract_with_python)"
else
  echo "ERROR: need one of 'jq' or 'python3' to parse the token response." >&2
  exit 1
fi

if [[ -z "$token" ]]; then
  echo "ERROR: token response contained no access_token:" >&2
  echo "$body" >&2
  exit 1
fi

printf '%s\n' "$token"
