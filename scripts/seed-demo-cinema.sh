#!/usr/bin/env bash
#
# seed-demo-cinema.sh — stand up a demo cinema in the Booking service via the gateway.
#
# Creates: 1 theater -> 2 screens -> a seat grid per screen -> 2 seat-types -> a few showtimes.
# So parts 2/3 ("my bookings") and the Phase-3 saga have real, seedable inventory to work with.
#
# WHY A CURL SCRIPT, NOT A LIQUIBASE SEED (plan step 6): showtime creation validates its movieId against
# Catalog synchronously (plan option 5C). A Liquibase INSERT would bypass that check and could seed a
# showtime pointing at a movie Catalog doesn't have. Driving it through the real POST /showtimes endpoint
# exercises the 5C path and guarantees every seeded showtime references a movie Catalog actually stores.
#
# PREREQUISITES:
#   - The stack is up (docker compose up) OR gateway + booking + catalog + discovery run locally.
#   - Catalog has synced movies (TMDB_API_KEY set, sync enabled) — the MOVIE_IDS below must exist in
#     Catalog, or POST /showtimes returns 404 BOOKING_MOVIE_NOT_FOUND (that's 5C doing its job).
#   - `jq` is installed (used to extract created ids).
#
# USAGE:
#   ./scripts/seed-demo-cinema.sh                # against the gateway on localhost:8080
#   GATEWAY=http://localhost:8080 ./scripts/seed-demo-cinema.sh
#
# The script is best-effort idempotent: duplicates (409 BOOKING_DUPLICATE) are tolerated and reported,
# and the seat-grid generator itself skips already-placed seats.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
API="$GATEWAY/api"

# Real TMDB ids that Catalog's popular/top-rated sync will have pulled in. Swap for any synced ids.
MOVIE_FIGHT_CLUB=550
MOVIE_MATRIX=603

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# POST helper: prints the body, returns it on stdout. A 409 is surfaced but not fatal (idempotency).
post() {
  local path="$1" body="$2"
  local resp code
  resp="$(curl -sS -w $'\n%{http_code}' -X POST "$API$path" \
    -H 'Content-Type: application/json' -d "$body")"
  code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [[ "$code" == 2* ]]; then
    echo "$body"
  else
    echo "  (HTTP $code) $body" >&2
    echo "$body"
  fi
}

say "Seat types"
STANDARD_ID="$(post /seat-types '{"name":"STANDARD","priceMultiplier":1.00}' | jq -r '.id // empty')"
VIP_ID="$(post /seat-types '{"name":"VIP","priceMultiplier":1.50}' | jq -r '.id // empty')"
echo "  STANDARD id=$STANDARD_ID  VIP id=$VIP_ID"

say "Theater"
THEATER_ID="$(post /theaters '{"name":"Downtown IMAX","location":"123 Main St"}' | jq -r '.id // empty')"
echo "  theater id=$THEATER_ID"

say "Screens"
SCREEN1_ID="$(post "/theaters/$THEATER_ID/screens" '{"name":"Screen 1","screenType":"SCREEN_3D"}' | jq -r '.id // empty')"
SCREEN2_ID="$(post "/theaters/$THEATER_ID/screens" '{"name":"Screen 2","screenType":"FRONT_SCREEN"}' | jq -r '.id // empty')"
echo "  screen1 id=$SCREEN1_ID  screen2 id=$SCREEN2_ID"

say "Seat grids (5 rows x 8 seats each)"
post "/screens/$SCREEN1_ID/seats/grid" "{\"rows\":5,\"seatsPerRow\":8,\"seatTypeId\":$STANDARD_ID}" >/dev/null
post "/screens/$SCREEN2_ID/seats/grid" "{\"rows\":5,\"seatsPerRow\":8,\"seatTypeId\":$VIP_ID}" >/dev/null
echo "  generated grids on both screens"

say "Showtimes (validated against Catalog — 5C)"
post /showtimes "{\"movieId\":$MOVIE_FIGHT_CLUB,\"screenId\":$SCREEN1_ID,\"showDate\":\"2026-07-10\",\"showTime\":\"19:30\",\"basePrice\":12.50}" >/dev/null
post /showtimes "{\"movieId\":$MOVIE_FIGHT_CLUB,\"screenId\":$SCREEN1_ID,\"showDate\":\"2026-07-10\",\"showTime\":\"22:00\",\"basePrice\":12.50}" >/dev/null
post /showtimes "{\"movieId\":$MOVIE_MATRIX,\"screenId\":$SCREEN2_ID,\"showDate\":\"2026-07-11\",\"showTime\":\"20:00\",\"basePrice\":15.00}" >/dev/null

say "Done. Explore:"
echo "  curl -s $API/theaters | jq"
echo "  curl -s $API/theaters/$THEATER_ID/screens | jq"
echo "  curl -s $API/screens/$SCREEN1_ID/seats | jq '. | length'"
echo "  curl -s $API/showtimes/movie/$MOVIE_FIGHT_CLUB | jq"
echo
echo "  # See the cut: a bogus movieId is rejected by 5C, NOT by a DB FK ->"
echo "  curl -s -o /dev/null -w '%{http_code}\\n' -X POST $API/showtimes \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"movieId\":99999999,\"screenId\":'$SCREEN1_ID',\"showDate\":\"2026-07-12\",\"showTime\":\"18:00\",\"basePrice\":10.00}'"
echo "  # -> 404 (BOOKING_MOVIE_NOT_FOUND): Catalog said 'no such movie'"
