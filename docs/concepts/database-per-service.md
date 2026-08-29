# Database per service

## What it is
Each service's data is **private**. Other services reach it only through the owning service's API or its events — **no shared tables, no cross-service joins, no cross-service foreign keys, no exceptions.** "Database" can mean a separate schema or a separate server; the rule is what matters.

## Why it exists
The tempting shortcut — many services, one database — quietly destroys the architecture:
- the schema becomes a **shared contract**, so one table change ripples through every service (lockstep deploys = distributed monolith);
- services bypass each other's business logic by writing directly to each other's tables;
- one service's runaway query starves everyone;
- you can never change the storage technology for one capability.

Private data restores **autonomy** — at a stated price.

## The price (say it out loud)
You give up, *across services*:
- **JOINs** → re-earn with API composition or CQRS read models (below).
- **Foreign keys** → become plain id columns + lookups.
- **ACID transactions** → re-earn with [sagas](saga-pattern.md).

Saying the cost is what earns trust. The price *is* the architecture working as intended.

## Re-earning queries
"Show my bookings with movie titles" now spans Booking and Catalog. Two answers, escalating:

1. **API composition** — the gateway/BFF or one service calls both and merges in memory. Fine for simple pages; awkward for filtering/sorting across services; breaks if a callee is down.
2. **CQRS read model** (Command Query Responsibility Segregation) — services publish events; a consumer maintains a denormalized, query-optimized view (e.g. a `booking_history` table or an Elasticsearch index holding booking + movie title + poster). Writes go to the owners; heavy reads hit the view. **Cost: the view is eventually consistent** — it lags by however long events take to arrive. This is denormalization, applied across services.

## ScreenMaster: the FK cuts
These JPA associations cross the chosen boundaries and become **ids + API/event lookups** (from [ARCHITECTURE_AND_SCHEMA.md §8.2](../ARCHITECTURE_AND_SCHEMA.md)):

| Association today | Becomes |
|---|---|
| `Showtime → Movie` | store `movieId` |
| `Showtime → Screen`, `Seat/Screen/SeatType` | store `screenId`, `seatId`, `seatTypeId` |
| `Booking → User` | store `userId` (from the JWT) |
| `Booking → Showtime` | store `showtimeId` |
| `BookingSeat → Seat` | store `seatId` **+ snapshot** of seat price/type at booking time |
| `PaymentTransaction → Booking` | store `bookingId` |

**Snapshot immutable facts into the consumer.** Booking already snapshots `seatPrice` into `BookingSeat` and `totalAmount` into `Booking` — extend that so Booking can render and validate without a synchronous call after the fact.

## The lab (field guide M2)
We implement "my bookings" *twice*: (a) API composition — Booking calls Catalog over HTTP and merges; (b) a tiny CQRS read model — Catalog publishes `MovieUpdated`, Booking keeps a local `movie_projections(id, title)` table and joins locally. Then stop Catalog: (a) breaks, (b) keeps serving possibly-stale titles. That's the staleness tradeoff, felt.

## Eventual consistency — the scary version
"So the user sees stale data?!" — yes, briefly, and that's a **business decision, not a bug**. The email arriving 2 s after payment is invisible; the seat map being 2 s stale is fine **because the seat-uniqueness constraint inside Booking stays strictly consistent** — the one place that must be. **Strong consistency *inside* a service boundary (one DB, real transactions); eventual consistency *between* services.** That sentence is the whole data story.

## Interview lens
"Per service. A shared DB makes the schema a public contract; deploys lock together and services bypass each other's invariants. The price is no cross-service JOIN (→ composition/CQRS) and no cross-service ACID (→ sagas) — and that price is the point."
