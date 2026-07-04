# Plan: Paginated & Filtered Listings (Booking) + repo-wide "listings must paginate" convention

- **Date:** 2026-07-05
- **Author:** Claude Code + Mohamed
- **Status:** IMPLEMENTED — A1 + B1 + C1 chosen; seat-types paginated; pages 0-indexed. Booking 43/43 & catalog 41/41 tests green.
- **Milestone:** M3 (Booking service — inventory read side)
- **Service:** `booking`
- **Schema §:** ARCHITECTURE_AND_SCHEMA.md §Theater/Inventory

> **Format note:** the convention (per `CLAUDE.md` / `docs/plans/README.md`) is an HTML plan from
> `_template.html`. You explicitly asked for a `.md` file, so this is Markdown. If you'd rather keep
> the plans directory uniform, say so and I'll port this to the HTML template.

---

## 1. Summary

`GET /theaters` (and its siblings `GET /seat-types`, `GET /theaters/{id}/screens`,
`GET /screens/{id}/seats`, and the `GET /showtimes/*` reads) currently call `findAll()` / `findBy…()`
and return the **entire** result set as a bare JSON array. With a large inventory this is an unbounded
response: it loads every row into memory, serializes all of it, and hands the client a payload whose
size we don't control. That's the classic "listing endpoint with no ceiling" smell.

This feature makes booking's list endpoints **paginated** and adds **dynamic filtering** via JPA
`Specification`s — mirroring the pattern **already proven in `catalog`** (`GET /movies` uses
`MovieFilter` + `MovieSpecifications` + `Page<…>`). We also establish a **repo-wide convention**:
*any endpoint that lists a collection must be paginated by default; returning an unbounded "all rows"
array is disallowed.*

The microservices lesson: an endpoint is a **contract with a blast radius**. "Return everything" is
fine in a monolith demo and a latent outage in production. Bounded pages + server-side filtering are
the production-grade default.

---

## 2. Milestone & schema link

- **Field-guide milestone:** M3 — Booking absorbs Theater + Scheduling; this hardens its read side.
- **Schema doc:** ARCHITECTURE_AND_SCHEMA.md — Theater/Screen/Seat/SeatType/Showtime inventory model.
- **In-repo precedent:** `catalog` already implements exactly this pattern for `GET /movies`
  (`catalog/.../controller/MovieController.java`, `controller/dto/MovieFilter.java`,
  `repository/spec/MovieSpecifications.java`, `service/MovieService.java`). Booking should look and
  feel identical so there's one paging idiom across services.

---

## 3. The repo-wide convention (the note you asked to include)

> **Convention — "Listings paginate; they never dump."**
>
> Every endpoint that returns a **collection of a resource** MUST be paginated and return a
> `Page`-shaped body (page content + `page` metadata: number, size, totalElements, totalPages). It
> MUST NOT return the full table as an unbounded array.
>
> - **Default page size** is bounded (proposed: `20`) and there is a **hard maximum** (proposed:
>   `100`) enforced server-side, so a client can't request `size=1000000`.
> - **Sorting** is restricted to an explicit **whitelist** of sortable fields per resource (an unknown
>   sort field is a coded `*_VALIDATION_ERROR`, never a leaked Hibernate 500). This mirrors
>   `MovieService.SORTABLE_FIELDS`.
> - **Filtering** is expressed as an optional filter DTO bound from query params → composed into JPA
>   `Specification` fragments (`AND`-combined; an absent field contributes a no-op). No
>   method-per-combination explosion, no string-built SQL.
> - **Exceptions** — genuinely small, fixed-cardinality lookups (e.g. an enum-like reference list that
>   is provably bounded) MAY return a full array, but the endpoint's Javadoc must say *why it's exempt*.
>   `seat-types` is a borderline case (see Open Questions).
>
> This convention lands in **`CLAUDE.md` (Conventions)** and a short **`docs/concepts/pagination-and-filtering.md`**
> so it's discoverable and enforced on every future listing endpoint.

---

## 4. What changes

### Endpoints affected in `booking`

| Endpoint | Today | After |
|---|---|---|
| `GET /theaters` | `List<TheaterResponse>` (all) | `Page<TheaterResponse>` + `TheaterFilter` (name, location) |
| `GET /theaters/{id}/screens` | `List<ScreenResponse>` (all under theater) | `Page<ScreenResponse>` + filter (name, screenType) |
| `GET /screens/{id}/seats` | `List<SeatResponse>` (all on screen) | `Page<SeatResponse>` + filter (seatRow, seatTypeId) |
| `GET /seat-types` | `List<SeatTypeResponse>` (all) | see Open Questions — likely exempt or lightly paged |
| `GET /showtimes/movie/{movieId}` etc. | `List<ShowtimeResponse>` (all) | `Page<ShowtimeResponse>` (Phase 2 — see Scope) |

### Files (for the primary target, `GET /theaters` — the rest follow the same shape)

| Kind | Where | What |
|---|---|---|
| NEW | `booking/.../controller/dto/TheaterFilter.java` | Query-param binding DTO (nullable, validated fields). |
| NEW | `booking/.../repository/spec/TheaterSpecifications.java` | Composable `Specification<Theater>` fragments + `from(filter)`. |
| EDIT | `booking/.../repository/TheaterRepository.java` | `extends JpaSpecificationExecutor<Theater>`. |
| EDIT | `booking/.../service/TheaterService.java` | `listTheaters(filter, pageable)` → `Page<Theater>`; sort whitelist. |
| EDIT | `booking/.../controller/TheaterController.java` | `GET /theaters` takes `@Valid TheaterFilter` + `@PageableDefault`, returns a paged body. |
| EDIT | `booking/.../exception/BookingErrorCode.java` | Reuse `BOOKING_VALIDATION_ERROR` for bad sort/filter (already exists — no new code). |
| EDIT | `booking/src/main/resources/application.yml` | `spring.data.web.pageable.max-page-size: 100`, `default-page-size: 20`; page serialization mode (see Option decision). |
| NEW | `docs/concepts/pagination-and-filtering.md` | Concept doc + the convention; linked from `docs/concepts/README.md`. |
| EDIT | `CLAUDE.md` | Add the listings-paginate convention under **Conventions**. |
| EDIT | tests | `TheaterControllerTest`, new `TheaterSpecificationsTest`, service test for the sort whitelist. |

---

## 5. Options

The three decisions worth choosing before coding. Each has a recommendation.

### Decision A — Response body shape (the JSON contract)

**A1 — `PagedModel` via `VIA_DTO` (RECOMMENDED).**
Return `Page<…>` from the controller but configure
`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` (or the equivalent
`spring.data.web.pageable` property) so it serializes as Spring Data's **stable** `PagedModel`
envelope (`content` + a nested `page: {size, number, totalElements, totalPages}`).
- ✅ Fixes the exact warning catalog is emitting today (its test log says: *"For a stable JSON
  structure, please use Spring Data's PagedModel … VIA_DTO"*). One config, applied once.
- ✅ Zero hand-written wrapper; identical shape across every paged endpoint and both services.
- ✅ Contract is stable — the default `PageImpl` serialization is explicitly documented as unstable.
- ❌ The envelope's field names are Spring's, not ours (fine — it's a well-known shape).
- **Best when:** you want one consistent, framework-blessed paging contract everywhere. This also
  retro-fixes catalog.

**A2 — Custom `PagedResponse<T>` record we own.**
A small `record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)`.
- ✅ We fully control the field names/shape.
- ❌ Reinvents what `PagedModel` already gives us; another type to test and keep in sync.
- **Best when:** you need a bespoke envelope (extra metadata) the framework can't express. We don't.

**A3 — Return raw `Page<T>` (status quo in catalog).**
- ❌ Emits the instability warning; JSON structure is not contractually stable across versions.
- **Best when:** never for a real contract — only acceptable as a throwaway.

### Decision B — How dynamic filtering is expressed

**B1 — JPA `Specification` fragments (RECOMMENDED — matches catalog exactly).**
`TheaterFilter` (nullable fields) → `TheaterSpecifications.from(filter)` composing per-field predicates
`AND`-combined, absent field = no-op.
- ✅ Identical to `MovieSpecifications`; type-safe Criteria API, no SQL injection surface.
- ✅ Any subset of filters composes without a repository-method explosion.
- ❌ Slightly more ceremony than a couple of derived queries (worth it for consistency + growth).
- **Best when:** filters may grow and you want one idiom repo-wide. ← our case.

**B2 — Derived `findBy…` query methods per filter combination.**
- ✅ Trivial for one or two fixed filters.
- ❌ Combinatorial explosion as filters multiply; diverges from the catalog idiom.
- **Best when:** exactly one, never-changing filter. Not future-proof here.

### Decision C — Scope of this change

**C1 — Booking inventory listings now; showtimes + catalog retrofit as fast-follow (RECOMMENDED).**
Do `GET /theaters` fully (the template), then `screens` and `seats` the same way. Land the convention
doc. Retrofit `catalog`'s `GET /movies` to `VIA_DTO` in the same PR (one-line config, kills its
warning). Defer the `GET /showtimes/*` list endpoints to an immediate follow-up so this PR stays
reviewable.
- ✅ One clean, reviewable slice that establishes the pattern + the rule.
- ✅ Catalog stops emitting the instability warning.
- ❌ Showtimes stay unpaginated for one more PR (tracked as follow-up).

**C2 — Everything in one PR (all booking + showtimes + catalog).**
- ✅ Convention fully enforced in a single sweep.
- ❌ Large diff; harder to review; more places to get the test matrix right at once.

**Recommended path: A1 + B1 + C1.**

---

## 6. Implementation tasks (recommended path)

1. **Add the convention + concept doc.** Write `docs/concepts/pagination-and-filtering.md` (rule,
   defaults, sort whitelist, Specification pattern, the `seat-types` exemption rationale). Link it from
   `docs/concepts/README.md` and add the rule to `CLAUDE.md` Conventions.
   *Done when:* both files reference the rule and the concept doc explains the "why".

2. **Enable stable page serialization.** Add `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`
   (or the `spring.data.web.pageable` properties) to booking; set `default-page-size: 20`,
   `max-page-size: 100`. *Done when:* a paged endpoint returns the `PagedModel` envelope, not raw `PageImpl`.

3. **`TheaterFilter` DTO.** Nullable `name` (case-insensitive contains), `location` (contains), with
   bean-validation where sensible. *Done when:* `?name=imax&location=cairo` binds; blank fields are no-ops.

4. **`TheaterSpecifications`.** `from(filter)` + per-field fragments (`noOp()` for absent), copied in
   spirit from `MovieSpecifications`. *Done when:* an empty filter yields an unrestricted-but-paged query.

5. **Repository + service.** `TheaterRepository extends JpaSpecificationExecutor<Theater>`;
   `TheaterService.listTheaters(TheaterFilter, Pageable)` returns `Page<Theater>` with a
   `SORTABLE_FIELDS` whitelist (`name`, `location`, `createdDate`). *Done when:* an unknown sort field
   throws `BOOKING_VALIDATION_ERROR`.

6. **Controller.** `GET /theaters` → `@Valid TheaterFilter` + `@PageableDefault(size = 20, sort = "name")`;
   map `Page<Theater>` → `Page<TheaterResponse>` via `.map(TheaterResponse::from)`. *Done when:*
   `GET /theaters?page=0&size=5&sort=name,asc&name=imax` returns a bounded, filtered, sorted page.

7. **Repeat for `screens` and `seats`.** Same five-file shape, screen/seat filters. *Done when:* both
   list endpoints are paged + filterable.

8. **Retrofit catalog to `VIA_DTO`.** One-line config so `GET /movies` stops emitting the instability
   warning. *Done when:* `MovieSearchControllerTest` no longer logs the PagedModel warning and asserts
   the stable envelope.

9. **Tests.** Controller tests assert the page envelope + `max-page-size` clamp + filter behavior;
   `TheaterSpecificationsTest` mirrors `MovieSpecificationsTest`; service test covers the sort whitelist.
   *Done when:* `./mvnw -q -pl booking test` (and catalog) is green.

---

## 7. Risks / microservices cost felt

- **Unbounded response = latent partial-failure / memory blowup.** The cost this surfaces is that a
  service must **defend its own resource limits**: no caller should be able to make the service load
  and serialize an arbitrarily large result. Pagination + `max-page-size` is the production defense.
- **Contract stability across services.** Returning raw `Page` leaks Spring's internal serialization
  as an implicit contract that can shift between versions — the very warning catalog emits.
  Standardizing on `PagedModel` (`VIA_DTO`) makes the paging contract explicit and identical in
  catalog and booking.
- **Sort-injection.** Passing an arbitrary `sort` property straight to Hibernate is an injection/500
  vector; the whitelist turns it into a coded 400. (Same lesson catalog already applies.)

---

## 8. Verification

| Check | Command | Expected |
|---|---|---|
| Default page is bounded | `curl -s localhost:8080/api/theaters` | ✓ `page.size == 20`, `content` ≤ 20 items, `page` metadata present |
| `max-page-size` clamps | `curl -s 'localhost:8080/api/theaters?size=100000'` | ✓ effective size clamped to 100, no full dump |
| Filter works | `curl -s 'localhost:8080/api/theaters?name=imax'` | ✓ only matching theaters; empty filter = unrestricted page |
| Sort whitelist enforced | `curl -s 'localhost:8080/api/theaters?sort=password,asc'` | ✓ `400` `BOOKING_VALIDATION_ERROR` ProblemDetail, not a 500 |
| Stable envelope | inspect body | ✓ `PagedModel` shape (`content` + nested `page`), no instability warning in logs |
| Catalog retrofit | `./mvnw -q -pl catalog test` | ✓ green, no "please use PagedModel" warning |

---

## 9. Open questions

- **`seat-types`:** it's a small, slowly-growing reference list. Exempt it (documented) or paginate it
  too for uniformity? *Leaning: paginate for uniformity — the rule is "listings paginate," and one
  exemption invites more.*
- **1-indexed vs 0-indexed pages:** Spring defaults to 0-indexed. Keep the default, or set
  `one-indexed-parameters: true` for a friendlier public API? *Leaning: keep 0-indexed (matches
  catalog; less surprise for Spring clients).*
- **Filter field set per resource:** which fields are worth filtering on for screens/seats beyond the
  obvious ones? Confirm the whitelist before coding tasks 3–7.
- **Retrofit catalog now or note-only:** include the catalog `VIA_DTO` one-liner in this PR
  (recommended, tiny) or split it out?