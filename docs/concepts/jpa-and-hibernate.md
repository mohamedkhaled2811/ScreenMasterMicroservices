# JPA & Hibernate: entities, mappings, repositories, transactions

## What it is
**JPA** is the Java standard for mapping objects to relational tables (the *specification*). **Hibernate** is the implementation Spring Boot uses. **Spring Data JPA** adds auto-generated repositories on top. Together they let you work with `Booking` objects instead of SQL rows — most of the time.

## Why it exists
Hand-writing SQL and mapping `ResultSet` columns to fields is repetitive and error-prone. JPA generates the SQL, manages a *persistence context* (a cache of loaded entities), and tracks changes so a dirty entity is auto-`UPDATE`d on commit.

## Mapping an entity

```java
@Entity
@Table(name = "bookings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"booking_reference"}))
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // DB auto-increments
    private int id;

    private String bookingReference;

    @Enumerated(EnumType.STRING)        // store "CONFIRMED", not 0,1,2  ← see gotcha
    private BookingStatus bookingStatus;

    @ManyToOne(fetch = FetchType.LAZY)  // many bookings → one showtime
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingSeat> seats = new ArrayList<>();

    private Instant expiresAt;
}
```

| Annotation | Meaning |
|---|---|
| `@Entity` | This class maps to a table. |
| `@Table` | Override table name / declare constraints. |
| `@Id` | Primary key field. |
| `@GeneratedValue` | How the PK is produced. `IDENTITY` = DB auto-increment; *assigned* (no annotation) = you set it (ScreenMaster's `Movie`/`Genre` use TMDB ids, `PaymentTransaction` uses PayPal's). |
| `@Enumerated(EnumType.STRING)` | Persist enum by **name**. The default is `ORDINAL` (by position) — fragile (see gotcha). |
| `@ManyToOne` / `@OneToMany` / `@ManyToMany` | Relationship cardinality. |
| `@JoinColumn` | The FK column on the owning side. |
| `mappedBy` | Marks the *inverse* (non-owning) side; the named field on the other entity owns the FK. |
| `fetch = LAZY/EAGER` | Load the association on access (LAZY) or always (EAGER). |
| `cascade` / `orphanRemoval` | Propagate persist/delete to children; delete children removed from the collection. |

## Repositories

```java
public interface BookingRepository extends JpaRepository<Booking, Integer> {
    Optional<Booking> findByBookingReference(String ref);     // derived query — name → SQL
    List<Booking> findByUserId(int userId);

    @Query("select count(bs) from BookingSeat bs " +
           "where bs.showtime.id = :showtimeId and bs.seat.id in :seatIds " +
           "and bs.booking.bookingStatus in ('PENDING','CONFIRMED')")
    long countActiveBySeat(int showtimeId, List<Integer> seatIds);
}
```
Spring generates the implementation. Method-name parsing covers simple queries; `@Query` (JPQL or native SQL) covers the rest.

## Dynamic queries with Specifications
Derived methods and `@Query` are fixed at compile time — fine for one or two filters, but "filter by **whatever** the caller sends" (title? genre? year? rating? any combination?) would need a method per combination. That's a combinatorial explosion. The answer is the **JPA Criteria API** wrapped in Spring Data's `Specification`:

```java
public interface MovieRepository
        extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> { }

// one composable predicate fragment per filter; absent fields contribute a no-op (always-true)
static Specification<Movie> titleContains(String t) {
    if (t == null || t.isBlank()) return (root, q, cb) -> cb.conjunction();   // no restriction
    return (root, q, cb) -> cb.like(cb.lower(root.get("title")), "%" + t.toLowerCase() + "%");
}

Specification<Movie> spec = Specification.allOf(titleContains(f.title()), hasGenre(f.genreId()), ...);
Page<Movie> page = repo.findAll(spec, pageable);   // AND-combined, with paging + sorting
```

Each present filter adds one predicate; the rest no-op. It's **type-safe and injection-proof** (no string concatenation) and composes cleanly with `Pageable`. This rebuilds the monolith's `MovieSpecification` (schema doc §1) inside the Catalog service — see `catalog/.../repository/spec/MovieSpecifications.java`.

> **Paging + a to-many fetch don't mix in one query.** If you `@EntityGraph`-fetch a `@ManyToMany` *and* page, Hibernate can't apply `LIMIT` in SQL (each parent spans multiple child rows) and paginates **in memory** — the "firstResult/maxResults specified with collection fetch; applying in memory" warning. The fix is the **two-query pattern**: page the *ids* first (no collection fetch → SQL `LIMIT` is correct), then fetch exactly those ids *with* the collection. Catalog's `findMoviePage` does this; whitelist sortable fields so a caller can't sort by an arbitrary column.

## Transactions

```java
@Service
public class BookingService {
    @Transactional                       // one DB transaction around the whole method
    public BookingResponse create(BookingRequest req) {
        // double-booking guard, pricing, save Booking + BookingSeats — all atomic
    }
}
```
`@Transactional` opens a transaction on entry and commits on normal return / rolls back on a runtime exception. **The DDD rule: one transaction modifies one aggregate.** Across services there is no shared transaction — that's why we need [sagas](saga-pattern.md) and the [outbox](transactional-outbox.md).

## How we use it here
Each microservice owns its tables and its own `DataSource` → its own Hibernate. **No cross-service joins, no cross-service FKs** (see [database-per-service.md](database-per-service.md)). The FK columns that today cross future boundaries (`Showtime → Movie`, `Booking → User`, `BookingSeat → Seat`) become plain `id` columns + API/event lookups.

## Gotchas / interview lens
- **`ddl-auto`**: the monolith uses `update` (Hibernate edits the schema live). That's unsafe across many service instances. We switch to **[Liquibase](liquibase.md) migrations + `ddl-auto=validate`**.
- **Ordinal enums are a data-corruption bug waiting to happen** — reorder the enum and old rows now mean something else. Always `@Enumerated(EnumType.STRING)`. The schema doc flags `BookingStatus`, `Booking.paymentStatus`, and `ScreenType` as ordinal; fix during extraction.
- **N+1 queries**: lazy associations loaded in a loop fire one query per row. Use `JOIN FETCH` or an `@EntityGraph`.
- **LAZY needs an open session** — accessing a lazy field after the transaction closes throws `LazyInitializationException`. Map to a DTO inside the transaction.
