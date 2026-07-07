# Booking Inventory & Showtimes — Front-End Integration

> **Service:** booking · **Base URL:** `{{baseUrl}}` (gateway, e.g. `http://localhost:8080`) · **Last updated:** 2026-07-05

## Summary

This is the **first** front-end integration doc for the project — there is no front end yet, so this describes what the booking service already exposes so design and API integration can start.

The front end will have **two distinct parts**:

1. **Admin / inventory console** — where an operator defines the physical and scheduling data: **seat types**, **theaters**, **screens**, **seats** (one-by-one or a whole grid), and **showtimes**. **This is what is built today** and what this doc covers.
2. **Customer booking flow** — where a normal user browses showtimes, picks seats, and pays. **This is not built yet.** The gateway already reserves the `/api/bookings/**` path for it, but no endpoint answers there. Payment lives in a separate service (`/api/payments`, documented separately). Ignore the customer flow for now; build the admin console against the endpoints below.

**Build order matters** — the data forms a tree, and a create fails if its parent is missing:

```
Seat type  ─────────────┐  (referenced by every seat)
Theater ──► Screen ──► Seat
                └────► Showtime  (also references a movieId owned by the Catalog service)
```

So the console should let an operator create seat types and a theater first, then screens under a theater, then seats on a screen and showtimes on a screen. A **showtime's `movieId`** is **not** owned by booking — the operator picks a movie from the **Catalog** service (`GET /api/movies`, see [Where the `movieId` comes from](#where-the-movieid-comes-from-catalog-service)), and booking validates it live against Catalog at create time (see [Behaviour notes](#behaviour-notes)).

## Auth

**None yet — enforced later at the gateway.** Every endpoint below is currently open (no token required). Edge auth (Keycloak/JWT) lands in a later phase; when it does, these admin endpoints will require an operator role. Design the console assuming an `Authorization: Bearer <jwt>` header will be needed later, but do not send one now.

| Endpoint | Auth required | Role / scope | Token header |
|---|---|---|---|
| all endpoints below | No (yet) | — | — (later: `Authorization: Bearer <jwt>`) |

## Endpoints

> Base URL is the gateway. Paths below are what the FE calls (`/api/...`). The gateway strips `/api` before forwarding, so these are the real front-door paths.
> All **list** responses use the **paged envelope** shown at the [bottom of this section](#paged-envelope). Pages are **0-indexed**.
> Money-like values (`priceMultiplier`, `basePrice`) are **decimal numbers** (e.g. `1.50`, `45.00`), sent and returned as JSON numbers, **not** minor-unit integers.
> Timestamps: `showDate` is a calendar date `YYYY-MM-DD`; `showTime` is `HH:mm` (24-hour, e.g. `"19:30"`) — **not** a full ISO datetime.

---

### 1. Seat types

A seat type is a named pricing tier (e.g. STANDARD, PREMIUM, VIP). Its `priceMultiplier` scales a showtime's base price when a seat of that type is later booked (`seat total = showtime.basePrice × seatType.priceMultiplier`). Every seat references a seat type, so create these first.

#### `POST /api/seat-types` — create a seat type

**Request**

- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `name` | string | yes | Non-blank, max 60 chars. Must be unique — a duplicate name is `409 BOOKING_DUPLICATE`. |
  | `priceMultiplier` | number | yes | Must be **> 0**. Max 2 integer + 2 fraction digits (i.e. `0.01`–`99.99`). |

  ```json
  { "name": "VIP", "priceMultiplier": 1.50 }
  ```

**Response** — `201 Created`

```json
{ "id": 3, "name": "VIP", "priceMultiplier": 1.50 }
```

#### `GET /api/seat-types` — list seat types (paged)

**Request**

- Query params:

  | Param | Type | Default | Notes |
  |---|---|---|---|
  | `page` | int | `0` | 0-indexed |
  | `size` | int | `20` | max `100` (a larger value is silently clamped to 100) |
  | `sort` | string | `name,asc` | whitelisted: `name`, `priceMultiplier`, `createdDate` — any other field → `400 BOOKING_VALIDATION_ERROR` |
  | `name` | string | — | Case-insensitive substring match on name; absent = no filter |

**Response** — `200 OK` (paged envelope; each item is the seat-type shape above)

```json
{
  "content": [
    { "id": 1, "name": "STANDARD", "priceMultiplier": 1.00 },
    { "id": 3, "name": "VIP", "priceMultiplier": 1.50 }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 2, "totalPages": 1 }
}
```

#### `DELETE /api/seat-types/{seatTypeId}` — delete a seat type

- Path params: `seatTypeId` — number (long).
- **Response** — `204 No Content` (empty body). Unknown id → `404 BOOKING_SEAT_TYPE_NOT_FOUND`.

---

### 2. Theaters

A theater is the top of the inventory tree — a physical venue that owns screens.

#### `POST /api/theaters` — create a theater

**Request**

- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `name` | string | yes | Non-blank, max 150 chars. Unique — duplicate → `409 BOOKING_DUPLICATE`. |
  | `location` | string | no | Free text, max 255 chars. May be omitted or `null`. |

  ```json
  { "name": "Downtown Cineplex", "location": "Cairo, Tahrir Square" }
  ```

**Response** — `201 Created`

```json
{ "id": 12, "name": "Downtown Cineplex", "location": "Cairo, Tahrir Square" }
```

#### `GET /api/theaters` — list theaters (paged)

**Request**

- Query params:

  | Param | Type | Default | Notes |
  |---|---|---|---|
  | `page` | int | `0` | 0-indexed |
  | `size` | int | `20` | max `100` |
  | `sort` | string | `name,asc` | whitelisted: `name`, `location`, `createdDate` — others → `400 BOOKING_VALIDATION_ERROR` |
  | `name` | string | — | Case-insensitive substring match on name |
  | `location` | string | — | Case-insensitive substring match on location |

**Response** — `200 OK` (paged envelope; item shape = theater above)

```json
{
  "content": [
    { "id": 12, "name": "Downtown Cineplex", "location": "Cairo, Tahrir Square" }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

#### `DELETE /api/theaters/{theaterId}` — delete a theater

- Path params: `theaterId` — number (long).
- **Response** — `204 No Content`. Unknown id → `404 BOOKING_THEATER_NOT_FOUND`.

---

### 3. Screens

A screen belongs to a theater. Its owning theater comes from the **URL**, not the body, so the two can't disagree.

#### `POST /api/theaters/{theaterId}/screens` — create a screen in a theater

**Request**

- Path params: `theaterId` — number (long); the theater must exist → else `404 BOOKING_THEATER_NOT_FOUND`.
- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `name` | string | yes | Non-blank, max 100 chars. Unique **within the theater** — duplicate → `409 BOOKING_DUPLICATE`. |
  | `screenType` | string (enum) | yes | One of exactly: `FRONT_SCREEN`, `REAR_PROJECTION`, `SCREEN_3D`. Any other value → `400 BOOKING_VALIDATION_ERROR`. |

  ```json
  { "name": "Screen 1", "screenType": "SCREEN_3D" }
  ```

**Response** — `201 Created` (includes the owning `theaterId`)

```json
{ "id": 45, "name": "Screen 1", "screenType": "SCREEN_3D", "theaterId": 12 }
```

#### `GET /api/theaters/{theaterId}/screens` — list a theater's screens (paged)

**Request**

- Path params: `theaterId` — number (long). Scopes the result to that theater only.
- Query params:

  | Param | Type | Default | Notes |
  |---|---|---|---|
  | `page` | int | `0` | 0-indexed |
  | `size` | int | `20` | max `100` |
  | `sort` | string | `name,asc` | whitelisted: `name`, `screenType`, `createdDate` — others → `400 BOOKING_VALIDATION_ERROR` |
  | `name` | string | — | Case-insensitive substring match on name |
  | `screenType` | string (enum) | — | Exact match; one of `FRONT_SCREEN`, `REAR_PROJECTION`, `SCREEN_3D`. An unknown value → `400 BOOKING_VALIDATION_ERROR` |

**Response** — `200 OK` (paged envelope; item shape = screen above)

```json
{
  "content": [
    { "id": 45, "name": "Screen 1", "screenType": "SCREEN_3D", "theaterId": 12 }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

#### `DELETE /api/screens/{screenId}` — delete a screen

> Note the delete path drops the theater segment — it's `/api/screens/{screenId}`, not `/api/theaters/{id}/screens/{id}`.

- Path params: `screenId` — number (long).
- **Response** — `204 No Content`. Unknown id → `404 BOOKING_SCREEN_NOT_FOUND`.

---

### 4. Seats

A seat belongs to a screen and references a seat type. A seat is identified within its screen by the `(seatRow, seatNumber)` pair — that pair must be unique per screen.

#### `POST /api/screens/{screenId}/seats` — place one seat

**Request**

- Path params: `screenId` — number (long); the screen must exist → else `404 BOOKING_SCREEN_NOT_FOUND`.
- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `seatRow` | string | yes | Non-blank, max 8 chars (e.g. `"A"`, `"B"`). |
  | `seatNumber` | integer | yes | Positive (≥ 1). |
  | `seatTypeId` | number (long) | yes | Must reference an existing seat type → else `404 BOOKING_SEAT_TYPE_NOT_FOUND`. |

  A `(seatRow, seatNumber)` that already exists on the screen → `409 BOOKING_DUPLICATE`.

  ```json
  { "seatRow": "A", "seatNumber": 1, "seatTypeId": 3 }
  ```

**Response** — `201 Created` (includes owning `screenId`, the `seatTypeId`, and a denormalized `seatTypeName` for display)

```json
{
  "id": 501,
  "seatRow": "A",
  "seatNumber": 1,
  "screenId": 45,
  "seatTypeId": 3,
  "seatTypeName": "VIP"
}
```

#### `POST /api/screens/{screenId}/seats/grid` — bulk-generate a seat grid

Convenience endpoint so seeding a screen isn't dozens of calls. Generates an `rows × seatsPerRow` grid: rows are labelled `A, B, C, …` and seats within a row are numbered `1..seatsPerRow`. Every generated seat gets the same `seatTypeId`.

**Request**

- Path params: `screenId` — number (long); must exist → else `404 BOOKING_SCREEN_NOT_FOUND`.
- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `rows` | integer | yes | Positive, **max 26** (one letter per row, A–Z). |
  | `seatsPerRow` | integer | yes | Positive, **max 100**. |
  | `seatTypeId` | number (long) | yes | Must reference an existing seat type → else `404 BOOKING_SEAT_TYPE_NOT_FOUND`. |

  ```json
  { "rows": 3, "seatsPerRow": 4, "seatTypeId": 1 }
  ```

**Response** — `201 Created` — a **plain JSON array** (not a paged envelope) of the seats **actually created**.

```json
[
  { "id": 601, "seatRow": "A", "seatNumber": 1, "screenId": 45, "seatTypeId": 1, "seatTypeName": "STANDARD" },
  { "id": 602, "seatRow": "A", "seatNumber": 2, "screenId": 45, "seatTypeId": 1, "seatTypeName": "STANDARD" }
]
```

This endpoint is **idempotent**: positions that already exist on the screen are **skipped**, not rejected. Re-running the same grid (or extending it with more rows) returns only the newly created seats — so a second identical call returns `[]`. See [Behaviour notes](#behaviour-notes).

#### `GET /api/screens/{screenId}/seats` — list a screen's seats (paged)

**Request**

- Path params: `screenId` — number (long). Scopes the result to that screen only.
- Query params:

  | Param | Type | Default | Notes |
  |---|---|---|---|
  | `page` | int | `0` | 0-indexed |
  | `size` | int | `20` | max `100` |
  | `sort` | string | `seatRow,asc` then `seatNumber,asc` | whitelisted: `seatRow`, `seatNumber`, `id` — others → `400 BOOKING_VALIDATION_ERROR` |
  | `seatRow` | string | — | Exact (case-insensitive) match on the row label, e.g. `"A"` |
  | `seatTypeId` | number (long) | — | Only seats of this seat-type id |

**Response** — `200 OK` (paged envelope; item shape = seat above)

```json
{
  "content": [
    { "id": 501, "seatRow": "A", "seatNumber": 1, "screenId": 45, "seatTypeId": 3, "seatTypeName": "VIP" }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

#### `DELETE /api/seats/{seatId}` — delete a seat

> The delete path is `/api/seats/{seatId}` (no screen segment).

- Path params: `seatId` — number (long).
- **Response** — `204 No Content`. Unknown id → `404 BOOKING_SEAT_NOT_FOUND`.

---

### 5. Showtimes

A showtime schedules a movie on a screen at a date/time with a base price. It references **two** things: a `screenId` (owned by booking, validated locally) and a `movieId` (owned by the **Catalog** service, validated over the network at create time — see [Behaviour notes](#behaviour-notes)).

> The showtime read endpoints below return a **plain JSON array**, not a paged envelope. They are the current (monolith-mirroring) shape and are **not** paginated yet.

#### Where the `movieId` comes from (Catalog service)

**Booking does not own movies and has no "list movies" endpoint.** To create a showtime the operator must pick a movie, and the `movieId` for that comes from the **Catalog** service, through the same gateway. These two endpoints are all the showtime form needs; the full Catalog contract will get its own doc later.

- **`GET /api/movies`** — paged, filtered movie search. Use this to power a "pick a movie" typeahead/list in the showtime form; take the `id` of the chosen row as `movieId`. Paged envelope (same shape as booking's lists); pages are 0-indexed.

  Query params (all optional): `title` (case-insensitive substring), `genreId` (long), `language` (2-letter, e.g. `en`), `releaseYearFrom` / `releaseYearTo` (int, `1888`–`2100`), `minRating` (number `0`–`10`), `adult` (boolean). Paging: `page` (default `0`), `size` (default `20`, max `100`), `sort` (default `popularity,desc`; whitelisted: `title`, `releaseDate`, `voteAverage`, `popularity` — others → `400 CATALOG_VALIDATION_ERROR`).

  Each `content` row is a lean summary — **`id` is the value you send as `movieId`**:

  ```json
  {
    "content": [
      {
        "id": 27,
        "title": "The Matrix",
        "releaseDate": "1999-03-31",
        "voteAverage": 8.2,
        "posterPath": "/poster.jpg",
        "genres": [ { "id": 28, "name": "Action" }, { "id": 878, "name": "Science Fiction" } ]
      }
    ],
    "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
  }
  ```

- **`GET /api/movies/{id}`** — full detail for one movie. Use it to resolve a `movieId` (from a showtime response) back to a title/poster for display, since showtime responses carry only the id. Returns `200 OK`; unknown id → `404 CATALOG_MOVIE_NOT_FOUND`.

  ```json
  {
    "id": 27,
    "title": "The Matrix",
    "originalTitle": "The Matrix",
    "overview": "A hacker learns…",
    "tagline": "Welcome to the Real World.",
    "releaseDate": "1999-03-31",
    "runtime": 136,
    "status": "Released",
    "originalLanguage": "en",
    "popularity": 84.5,
    "voteAverage": 8.2,
    "voteCount": 24000,
    "posterPath": "/poster.jpg",
    "backdropPath": "/backdrop.jpg",
    "adult": false,
    "genres": [ { "id": 28, "name": "Action" } ]
  }
  ```

  `posterPath` / `backdropPath` are TMDB **relative** paths (e.g. `/abc.jpg`); prefix a TMDB image base URL to render them.

> **Catalog errors** use their own `code` prefix — `CATALOG_VALIDATION_ERROR` (400), `CATALOG_MOVIE_NOT_FOUND` (404), returned as the same `application/problem+json` shape. Don't confuse these with the `BOOKING_*` codes; a movie call that 404s returns `CATALOG_MOVIE_NOT_FOUND`, whereas a *showtime create* rejected for a bad movie returns `BOOKING_MOVIE_NOT_FOUND` (booking asked Catalog on your behalf).

#### `POST /api/showtimes` — create a showtime

**Request**

- Body (`application/json`):

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `movieId` | number (long) | yes | Validated **live against Catalog**. Unknown movie → `404 BOOKING_MOVIE_NOT_FOUND`; Catalog unreachable → `503 BOOKING_CATALOG_UNAVAILABLE`. |
  | `screenId` | number (long) | yes | Must be an existing screen → else `404 BOOKING_SCREEN_NOT_FOUND`. |
  | `showDate` | string (date) | yes | `YYYY-MM-DD`. |
  | `showTime` | string | yes | `HH:mm` 24-hour (e.g. `"19:30"`). **Not** a full ISO datetime. |
  | `basePrice` | number | yes | Must be **> 0**. Up to 8 integer + 2 fraction digits. |

  The `(screen, movie, date, time)` combination must be unique → else `409 BOOKING_DUPLICATE`.

  ```json
  {
    "movieId": 27,
    "screenId": 45,
    "showDate": "2026-07-20",
    "showTime": "19:30",
    "basePrice": 45.00
  }
  ```

**Response** — `201 Created`

```json
{
  "id": 900,
  "movieId": 27,
  "screenId": 45,
  "showDate": "2026-07-20",
  "showTime": "19:30",
  "basePrice": 45.00,
  "status": "SCHEDULED"
}
```

`status` is one of `SCHEDULED`, `CANCELLED`, `COMPLETED`, `SOLD_OUT`. A newly created showtime is always `SCHEDULED`. Note the response carries only `movieId` — **no movie title**. Resolving the title is the Catalog service's job (a future customer-flow concern); for now the admin console must look the title up from Catalog itself if it wants to show one.

#### `GET /api/showtimes/{id}` — get one showtime

- Path params: `id` — number (long).
- **Response** — `200 OK`, showtime shape above. Unknown id → `404 BOOKING_SHOWTIME_NOT_FOUND`.

#### `GET /api/showtimes/movie/{movieId}` — all showtimes for a movie

Returns every showtime for a movie (any date), ordered by date then time.

- Path params: `movieId` — number (long).
- **Response** — `200 OK`, a **plain JSON array** of showtime shapes (empty array `[]` if none). Not paged.

```json
[
  { "id": 900, "movieId": 27, "screenId": 45, "showDate": "2026-07-20", "showTime": "19:30", "basePrice": 45.00, "status": "SCHEDULED" }
]
```

#### `GET /api/showtimes/movie/upcoming/{movieId}` — upcoming showtimes for a movie

Same as above but only showtimes **on or after today** (server's clock), ordered by date then time.

- Path params: `movieId` — number (long).
- **Response** — `200 OK`, a plain JSON array (empty `[]` if none). Not paged.

#### `GET /api/showtimes/screen/{screenId}` — all showtimes on a screen

- Path params: `screenId` — number (long).
- **Response** — `200 OK`, a plain JSON array (empty `[]` if none). Not paged.

#### `DELETE /api/showtimes/{id}` — delete a showtime

- Path params: `id` — number (long).
- **Response** — `204 No Content`. Unknown id → `404 BOOKING_SHOWTIME_NOT_FOUND`.

---

<a name="paged-envelope"></a>
**Paged envelope** (shape of every **list** response — theaters, screens, seats, seat-types):

```json
{
  "content": [ /* array of the item shape documented for that endpoint */ ],
  "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 }
}
```

> Reminder: the **seat-grid** endpoint (`POST .../seats/grid`) and **all `GET /api/showtimes/...` list endpoints** are **plain arrays**, not this envelope.

## Behaviour notes

- **Build the tree top-down.** A create fails with a `404` if its parent doesn't exist: a screen needs an existing theater, a seat needs an existing screen **and** an existing seat type, a showtime needs an existing screen. Disable/guard the relevant form until the parent is chosen.
- **Showtime create depends on the Catalog service being up.** The `movieId` is verified with a live call to Catalog. So `POST /api/showtimes` can fail in a way the other creates can't: `503 BOOKING_CATALOG_UNAVAILABLE` means "couldn't reach Catalog, try again later" (transient — offer a retry), whereas `404 BOOKING_MOVIE_NOT_FOUND` means "Catalog says that movie doesn't exist" (the user must fix the id). Branch on the `code` to tell them apart.
- **The seat-grid generator is idempotent.** Re-running it (or extending a grid with more rows) skips positions that already exist and returns only the seats it actually created — a repeated identical call returns `[]`, not a `409`. A single-seat `POST .../seats` for an existing position, by contrast, **is** a `409 BOOKING_DUPLICATE`.
- **Uniqueness rules:** seat-type `name` (global), theater `name` (global), screen `name` (per theater), seat `(row, number)` (per screen), showtime `(screen, movie, date, time)` (per screen). Any violation → `409 BOOKING_DUPLICATE`.
- **`showTime` is `HH:mm`.** Send `"19:30"`, not `"19:30:00"` or an ISO datetime. It's returned in the same `HH:mm` form.
- **Prices are decimals, not minor units.** `priceMultiplier` and `basePrice` are plain JSON numbers (`1.50`, `45.00`), not integer cents.
- **Empty filters are fine.** Omitting all filter params on a list endpoint returns the full paged list, not an error.
- **Off-whitelist sort is a `400`, not ignored.** Sorting by a field not in that endpoint's whitelist returns `400 BOOKING_VALIDATION_ERROR` — validate the sort field in the UI before sending.
- **The customer booking + payment flow doesn't exist here yet.** `/api/bookings/**` is routed but unimplemented. Don't build against it from this doc.

## Errors

All errors return `Content-Type: application/problem+json`. Branch on the **`code`** field, not the HTTP status or the message text (`detail` is human-readable and may change).

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "name: must not be blank",
  "code": "BOOKING_VALIDATION_ERROR",
  "instance": "/api/seat-types"
}
```

| `code` | HTTP | When it fires |
|---|---|---|
| `BOOKING_VALIDATION_ERROR` | 400 | Bad body (blank/oversize/missing field, non-positive price/multiplier), an unknown enum value (`screenType`, showtime `status`), a non-numeric id in the path, or an off-whitelist `sort` field. |
| `BOOKING_THEATER_NOT_FOUND` | 404 | Theater id doesn't exist (delete, or as the parent of a screen create/list). |
| `BOOKING_SCREEN_NOT_FOUND` | 404 | Screen id doesn't exist (delete, seat/showtime create, seat list). |
| `BOOKING_SEAT_NOT_FOUND` | 404 | Seat id doesn't exist (delete). |
| `BOOKING_SEAT_TYPE_NOT_FOUND` | 404 | Seat-type id doesn't exist (delete, or referenced by a seat / grid create). |
| `BOOKING_SHOWTIME_NOT_FOUND` | 404 | Showtime id doesn't exist (get / delete). |
| `BOOKING_MOVIE_NOT_FOUND` | 404 | On showtime create: Catalog answered that the `movieId` doesn't exist. |
| `BOOKING_CATALOG_UNAVAILABLE` | 503 | On showtime create: the Catalog service couldn't be reached to validate the `movieId`. Transient — retry later. |
| `BOOKING_DUPLICATE` | 409 | A uniqueness rule was violated (see [Behaviour notes](#behaviour-notes)). |
| `BOOKING_INTERNAL_ERROR` | 500 | Unexpected server error; body carries no internal detail. Treat as a generic failure. |
