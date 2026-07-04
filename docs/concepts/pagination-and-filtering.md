# Pagination & dynamic filtering

**Why this, here:** a listing endpoint that returns "all rows" is an unbounded response — it loads
every row into memory, serializes all of it, and hands the caller a payload whose size *the caller*
controls, not us. Fine in a monolith demo; a latent outage in production (a theater with 10k seats, a
catalog with 100k movies). Every list endpoint in this repo is therefore **paged by default** and
filters **dynamically** via JPA `Specification`s. Catalog's `GET /movies` is the reference; booking's
inventory listings follow the same shape.

## The convention — "Listings paginate; they never dump"

Every endpoint that returns a **collection of a resource** MUST be paginated and return a
`Page`-shaped body. It MUST NOT return the full table as an unbounded array. Concretely:

- **Bounded default size** (`WebPagingConfig.DEFAULT_PAGE_SIZE` = 20) and a **hard maximum**
  (`MAX_PAGE_SIZE` = 100) enforced server-side — a client can't request `size=1000000`.
- **Sorting is whitelisted** per resource. An unknown/injected sort field is a coded
  `*_VALIDATION_ERROR` (400), never passed to Hibernate (which would surface as an opaque 500).
- **Filtering** is an optional filter DTO bound from query params → composed `Specification`
  (`AND`-combined; an absent field is a no-op). No repository method per filter combination.
- **Stable JSON contract** via `pageSerializationMode = VIA_DTO` (Spring Data's `PagedModel`
  envelope), not the internal `PageImpl` shape.
- **No exemptions.** Even small reference lists (booking's `seat-types`) are paged, so there's one
  idiom everywhere and "it's small today" never rots into an unbounded endpoint tomorrow.

## The four moving parts

1. **Filter DTO** (`controller/dto/…Filter.java`) — a `record` whose components are the query params.
   Every field is nullable/optional; Spring binds `?name=imax` onto the matching component. Bean
   validation here is the first line of input defence (catalog's `MovieFilter` rejects a rating > 10).

2. **Specifications** (`repository/spec/…Specifications.java`) — one `Specification` fragment per
   filter field, `AND`-combined by `from(filter)`. An absent field returns an always-true `noOp()`
   (`cb.conjunction()`), so an empty filter is an unrestricted-but-paged query and any subset composes.
   Built on the JPA Criteria API → type-safe, database-agnostic, no string SQL to inject into.

3. **Repository** — `extends JpaSpecificationExecutor<T>`, giving `findAll(spec, pageable)`.
   - A plain to-one/no-collection entity (theater, screen, seat-type) pages correctly in one SQL query.
   - An entity whose DTO mapper reads a **lazy association** after the transaction (booking's seat →
     `seatType.name`, under `open-in-view: false`) uses a **two-step** fetch (`findSeatPage`): page the
     ids (collection-free, so SQL `LIMIT` is exact), then re-fetch that page *with* the association via
     `@EntityGraph` and restore the sort order. Same trick catalog uses for movies + genres — required
     there because genres is a *collection* (paginating a collection-fetch forces in-memory `LIMIT`);
     booking's seatType is to-one so it's belt-and-braces, kept for a uniform idiom.

4. **Service + controller** — the service validates the sort against the resource's whitelist, then
   runs the paged spec. The controller takes `@Valid …Filter` + `@PageableDefault(...) Pageable`,
   returns `Page<…Response>` via `.map(Response::from)`. `page`/`size`/`sort` ride on the `Pageable`,
   bound separately from the filter.

## Where the caps and the envelope live

`config/WebPagingConfig.java` (in each web-facing service) does two things:

- `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` — serialize every `Page` as the stable
  `PagedModel` envelope: `{ content: [...], page: { size, number, totalElements, totalPages } }`.
  Without this, Spring logs *"For a stable JSON structure, please use PagedModel … VIA_DTO"* and the
  JSON shape isn't a contract you can rely on across versions.
- a `PageableHandlerMethodArgumentResolverCustomizer` that sets the fallback page size and the
  **max page size** — the teeth behind the cap. Setting it in code (not only via
  `spring.data.web.pageable.*` properties) guarantees the ceiling regardless of Boot property-binding
  differences, and keeps the policy next to its rationale.

Pages are **0-indexed** (Spring's default) — we did not enable one-indexed params, so Spring clients
see no surprise.

## The stable envelope (what a client sees)

```json
{
  "content": [ { "id": 1, "name": "Downtown IMAX", "location": "Cairo" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

## Microservices cost this addresses

- **A service must defend its own resource limits.** No caller should be able to make the service load
  and serialize an arbitrarily large result — pagination + `MAX_PAGE_SIZE` is that defense.
- **Contract stability.** Returning a raw `Page` leaks Spring's internal serialization as an implicit,
  version-fragile contract. `VIA_DTO`'s `PagedModel` makes it explicit and identical across services.
- **Sort-injection.** An arbitrary `sort` property passed straight to Hibernate is a 500/injection
  vector; the whitelist turns it into a coded 400.

## In this repo

| Piece | Catalog (reference) | Booking |
|---|---|---|
| Filter DTO | `MovieFilter` | `TheaterFilter`, `ScreenFilter`, `SeatFilter`, `SeatTypeFilter` |
| Specifications | `MovieSpecifications` | `Theater/Screen/Seat/SeatTypeSpecifications` |
| Two-step lazy fetch | `MovieRepository.findMoviePage` (genres) | `SeatRepository.findSeatPage` (seatType) |
| Sort whitelist | `MovieService.SORTABLE_FIELDS` | `TheaterService.*_SORTABLE` |
| Caps + envelope | `config/WebPagingConfig` | `config/WebPagingConfig` |
