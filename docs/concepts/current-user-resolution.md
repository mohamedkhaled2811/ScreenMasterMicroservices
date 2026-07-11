# Current-user resolution — a `@CurrentUser` argument resolver as a Phase-7 seam

## What it is
A tiny piece of Spring MVC wiring — a custom `HandlerMethodArgumentResolver` plus a `@CurrentUser`
annotation — that binds "who is making this request" to a controller parameter, so a method can declare
`create(@CurrentUser String userId, …)` and receive the caller's id without knowing where it came from.

## Why it exists (the ordering problem it solves)
Booking needs a `user_id` on every booking. But **Identity (Keycloak) is Phase 7** — the last milestone —
and we're building booking creation in Phase 2. So there is no authentication yet, and there is
deliberately no `users` table in Booking (users aren't Booking's aggregate; `user_id` is a *cross-service
reference by id*, exactly like a showtime's `movieId` references Catalog).

The resolver is the **single seam** between "who is calling" and the rest of the service. Funnelling
identity through one class means the Phase-7 switch to a real JWT is a change to *one method body*, not a
rewrite of every controller, service, and the database column:

| | Today (pre-Keycloak) | Phase 7 (Keycloak) |
|---|---|---|
| Where the id comes from | the `X-User-Id` request header | the JWT `sub` claim |
| Missing identity | coded `400 BOOKING_VALIDATION_ERROR` | `401` at the resource server |
| Controller signature | `@CurrentUser String userId` | **unchanged** |
| `bookings.user_id` column | `VARCHAR(36)` (a UUID string) | **unchanged** |

The column is typed `VARCHAR(36)` from day one because a Keycloak `sub` is a UUID string — so today's
`X-User-Id` value and tomorrow's `jwt.getSubject()` land in the *same* column with no migration.

## Why not just a `@RequestHeader` or a path variable?
- **Not the body / not the URL.** *Who you are* is an ambient property of the request, not something the
  caller states in the payload — otherwise a caller could book "as" someone else by changing a field. A
  path like `/bookings/user/{userId}` has the same leak and, worse, has nowhere natural to put identity on
  a `POST`. Keeping identity out of the URL/body is also what makes the JWT swap a no-op.
- **One place to swap.** A raw `@RequestHeader("X-User-Id")` on every method would mean editing every
  method in Phase 7. The resolver centralizes the read (and the "missing → coded error" rule) so the swap
  touches one file.

## How we use it here
In `booking`:
- `security/CurrentUser.java` — the `@CurrentUser` marker annotation (parameter-level).
- `security/CurrentUserArgumentResolver.java` — reads `X-User-Id`; a missing/blank header throws a coded
  `BOOKING_VALIDATION_ERROR` (never a raw 500, never a silent `null` that would create rows under a bogus
  user). The Phase-7 change lives entirely in this class's `resolveArgument`.
- `config/WebMvcConfig.java` — registers the resolver via `WebMvcConfigurer.addArgumentResolvers`.

`BookingController`'s `POST /bookings` and `GET /bookings/my` both take `@CurrentUser String userId`.

## Gotchas / interview lens
- **`@WebMvcTest` and the resolver.** A web-slice test must `@Import(WebMvcConfig.class)` for the resolver
  to be registered — otherwise `@CurrentUser` won't resolve and the slice fails confusingly.
- **This is not security.** The header is trusted blindly today; anyone can send any `X-User-Id`. That's
  acceptable *only* because the lab has no auth yet and the gateway isn't locking anything down until
  Phase 7. In production the id must come from a verified token, never a client-set header — that's the
  whole point of moving the read into the resolver so the trust source can change in one place.
- **Interview framing:** "I modelled `user_id` as an opaque cross-service reference and isolated identity
  behind one argument resolver, so introducing Keycloak later is a one-method change with no schema
  migration" — a concrete story about designing for a known-future change without over-building it now.

See [security-jwt-oauth2.md](security-jwt-oauth2.md) (Phase 7, where the resolver's source becomes the
JWT), [database-per-service.md](database-per-service.md) (`user_id` as a cross-service ref), and
[error-handling-problemdetail.md](error-handling-problemdetail.md) (the coded 400 on a missing header).
