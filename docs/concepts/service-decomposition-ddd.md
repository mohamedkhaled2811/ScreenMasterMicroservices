# Service decomposition with DDD: where to cut

## What it is
The hardest microservices question isn't "how do services talk" — it's **"where do you draw the boundaries."** Domain-Driven Design (DDD) is the vocabulary for cutting well. Bad cuts give you a *distributed monolith*: all the tax, none of the benefits.

## The DDD ideas you actually need

- **Ubiquitous language** — within a part of the business, everyone (code included) uses the same word for the same thing. When one word means different things to different people, you've found a seam.
- **Bounded context** — a boundary inside which a model and its language are consistent. "Movie" in *Catalog* = title, genre, runtime, poster. "Movie" in *Booking* = just an id + a name to print on a ticket. Forcing one giant `Movie` class to serve both is how monoliths grow god-models. **A bounded context is the natural unit of a microservice** — the most quotable line here.
- **Subdomain** — *core* (your differentiator: booking & pricing), *supporting* (catalog management), *generic* (notifications, identity — buy/use Keycloak, don't build). Spend your best engineering on the core.
- **Aggregate** — a cluster of objects treated as one consistency unit, changed through its root. `Booking` + its `BookingSeat`s is an aggregate. **Rule: one transaction modifies one aggregate.** A service owns *whole* aggregates, never splits one.

## ScreenMaster, decomposed

```
   browsers/mobile ─▶ API GATEWAY ─┬─▶ CATALOG    (movies, genres, TMDB sync)   read-heavy, cache-friendly
                                    ├─▶ BOOKING    (showtimes, seats, bookings)  CORE: contended writes, strict seat consistency
                                    ├─▶ PAYMENT    (PayPal/Stripe, webhooks)     wraps slow/flaky outside world
                                    ├─▶ IDENTITY   (Keycloak — users, tokens)    generic: don't build
                                    └─▶ NOTIFICATION (emails) ← event consumer    fire-and-forget, no one calls it
```

Narrate the *reasoning*, not the boxes:
- **Catalog** — read-heavy, tolerates staleness → separate scaling profile.
- **Booking** — the core domain; strict consistency on seat uniqueness (a hard DB constraint *inside* the service).
- **Payment** — isolate the slow, flaky external providers behind one service.
- **Notification** — pure event consumer; emails can be late.
- **Identity** — generic; delegate to Keycloak.

(Compare with the finer-grained split in [ARCHITECTURE_AND_SCHEMA.md §8.1](../ARCHITECTURE_AND_SCHEMA.md): Identity, Catalog, Theater, Scheduling, Booking, Payment, Notification. For learning we collapse Theater + Scheduling into Booking, since the booking transaction needs their facts and only needs *immutable* data from them.)

## How to find boundaries in general
Decompose by **business capability** ("manage catalog", "take bookings", "collect payment"), then validate each candidate with three tests:
1. Could a small team own it end-to-end?
2. Can it change and deploy without forcing changes elsewhere?
3. Does it own all the data it needs for its core job?

Fail test 2 and it's a module wearing a service costume.

## Anti-patterns to name-drop
- **Entity services** — cutting by *noun/table* (`UserService`, `MovieService`, `SeatService`) → anemic CRUD wrappers where every flow spans five services. Cut by *capability*, not by table.
- **Nanoservices** — so fine-grained the network outweighs the logic.
- **Distributed monolith** — services that must deploy together, share a DB, or sit in deep synchronous chains. Smells: a coordinated release calendar, one schema change breaking three repos, latency = sum of six hops. Fix: break the data coupling, or **merge services back** (a valid, senior refactor).

## Strangler fig — the migration answer
"How would you migrate a monolith?" → *process, not heroics*: put a routing façade (the gateway) in front of the monolith → pick the seam with the best value-to-risk ratio (often something event-friendly like notifications) → build it as a service and flip its routes → repeat. The monolith shrinks release by release; the business never stops; every step is reversible. Pair with an **anti-corruption layer** (a translator at the boundary) when the new service must still talk to the old model.

## Interview lens
"A bounded context is the natural unit of a microservice." + "Cut by capability, not by table." + "Merging services back is a valid refactor." Three sentences that cover 80% of boundary questions.
