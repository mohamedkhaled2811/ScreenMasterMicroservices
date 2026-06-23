# Build Plan — splitting ScreenMaster into microservices

> The roadmap. Derived from the field guide's lab ([microservices-interview-field-guide.md §10](microservices-interview-field-guide.md)) and the proposed decomposition ([ARCHITECTURE_AND_SCHEMA.md §8](ARCHITECTURE_AND_SCHEMA.md)). Each step states **goal · what we build · the concept it teaches · "done when"**. Concept links point at [docs/concepts/](concepts/).
>
> **Principle:** ugly code, working demos. The value is *feeling* each microservices cost, then being able to narrate it. Don't gold-plate.

## The shape we're building toward

```
browsers / curl ─▶ GATEWAY ─┬─▶ CATALOG      (movies, genres, TMDB)        catalog-db
                            ├─▶ BOOKING      (showtimes, seats, bookings)  booking-db   ← core
                            ├─▶ PAYMENT      (fake provider, FAIL_RATE)    (no db / tiny)
                            ├─▶ NOTIFICATION (consumes BookingConfirmed)   (no db / dedupe table)
                            └─▶ IDENTITY     (Keycloak)                     keycloak-db
        all services register with ─▶ EUREKA        async events over ─▶ RABBITMQ
        traces ship to ─▶ ZIPKIN
```

Five business services + gateway + Eureka, on Docker Compose, with **database-per-service**. (We collapse the schema doc's Theater + Scheduling into Booking, since the booking transaction only needs *immutable facts* from them — see [service-decomposition-ddd.md](concepts/service-decomposition-ddd.md).)

---

## Phase 0 — Foundations (you are here)
**Goal:** understand the system and set up the workspace before writing services.

- [x] **0.1** Read both source docs; write the concept library ([docs/concepts/](concepts/)) and project guidance ([CLAUDE.md](../CLAUDE.md)).
- [x] **0.2** This build plan.
- [ ] **0.3** Decide & scaffold the **multi-module Maven layout**: turn the current single-module `pom.xml` into a **parent pom** (`<packaging>pom</packaging>`, `<modules>`), and create empty child modules: `gateway`, `discovery`, `catalog`, `booking`, `payment`, `notification`. *(Concept: [spring-boot-annotations.md](concepts/spring-boot-annotations.md) — one `@SpringBootApplication` per module.)*

**Done when:** `./mvnw -q -pl discovery -am package` builds an empty module; the repo tree shows one folder per service.

> *Optional pre-work the schema doc recommends but the lab doesn't strictly need:* add **Flyway** and pin the schema, and convert **ordinal enums → `@Enumerated(STRING)`**. We'll fold these into each service as we carve it out, rather than touching the monolith first.

---

## Phase 1 — Skeleton & infrastructure  *(field guide M1)*
**Goal:** five services + gateway + discovery up on Compose, talking through the front door. No business logic yet.

- [ ] **1.1 Eureka server** (`discovery`) — `@EnableEurekaServer`, runs on 8761. *(Concept: [service-discovery.md](concepts/service-discovery.md))*
- [ ] **1.2 Minimal Spring Boot apps** for `catalog`, `booking`, `payment` (fake provider: returns 200 after ~300 ms, `FAIL_RATE` env var for later), `notification`. Each registers with Eureka and exposes a trivial `/health`-style endpoint. *(Concepts: [spring-boot-annotations.md](concepts/spring-boot-annotations.md), [spring-web-annotations.md](concepts/spring-web-annotations.md))*
- [ ] **1.3 Gateway** — Spring Cloud Gateway routes `/api/movies/**`→catalog, `/api/bookings/**`→booking, `/api/payments/**`→payment via `lb://`. *(Concept: [api-gateway-and-bff.md](concepts/api-gateway-and-bff.md))*
- [ ] **1.4 Compose file** — every service + **two Postgres** (catalog-db, booking-db) + RabbitMQ + Zipkin. Service name = DNS name. *(Concept: [containers-and-compose.md](concepts/containers-and-compose.md))*

**Done when:** `docker compose up --build` brings the system up; a request to the gateway reaches a service; Eureka dashboard (8761) shows all registered.
**Talk track:** *"I run a five-service system locally with isolated databases — here's the compose file."*

---

## Phase 2 — Feel the missing JOIN  *(field guide M2)*
**Goal:** experience cross-service queries and the staleness tradeoff. Booking needs movie titles it no longer owns.

- [ ] **2.1 Catalog**: real `movies`/`geners` tables (Flyway), a `GET /api/movies/{id}` returning a `MovieDto`. *(Concept: [jpa-and-hibernate.md](concepts/jpa-and-hibernate.md))*
- [ ] **2.2 "My bookings" — way A (API composition):** Booking calls Catalog over HTTP (`RestClient`) and merges titles in memory. *(Concepts: [spring-web-annotations.md](concepts/spring-web-annotations.md), [database-per-service.md](concepts/database-per-service.md))*
- [ ] **2.3 "My bookings" — way B (CQRS read model):** Catalog publishes `MovieUpdated` to RabbitMQ; Booking keeps a local `movie_titles(id, title)` table and joins locally. *(Concepts: [database-per-service.md](concepts/database-per-service.md), [rabbitmq.md](concepts/rabbitmq.md), [sync-vs-async-comms.md](concepts/sync-vs-async-comms.md))*
- [ ] **2.4 Break it:** stop Catalog → way A fails, way B serves possibly-stale titles.

**Done when:** both endpoints work with Catalog up; with Catalog down, A errors and B still answers.
**Talk track:** *"I implemented both answers to cross-service queries and can articulate the staleness tradeoff from experience."*

---

## Phase 3 — The booking saga (orchestrated)  *(field guide M3)*
**Goal:** re-earn the cross-service transaction with a saga + compensation.

- [ ] **3.1 Booking core:** `POST /api/bookings` → create `PENDING`, **hold seats** with the local double-booking guard (`UNIQUE`/active-booking check). *(Concepts: [jpa-and-hibernate.md](concepts/jpa-and-hibernate.md), [saga-pattern.md](concepts/saga-pattern.md))*
- [ ] **3.2 Orchestrate:** Booking calls Payment **synchronously** → on approve, `CONFIRMED`; on decline, **compensate** (release seats, `CANCELLED`). Persist saga state on the booking row so a crashed orchestrator resumes. *(Concept: [saga-pattern.md](concepts/saga-pattern.md))*
- [ ] **3.3 Expiry sweeper:** a `@Scheduled` job expires stale `PENDING` bookings (the 15-min `expiresAt`) and frees seats — the saga timeout. *(This is a flagged gap in the monolith — we build it.)*
- [ ] **3.4 Test:** `FAIL_RATE=0.3`, fire 50 bookings, assert every failure compensated — **zero orphaned seat holds.**

**Done when:** the 50-booking script leaves no leaked seats under 30% payment failure.
**Talk track:** *"My saga survives a 30% payment failure rate with zero leaked seat holds — I verified with a script."*

---

## Phase 4 — Outbox → RabbitMQ → idempotent consumer  *(field guide M4)*
**Goal:** publish events reliably and consume them exactly once *in effect*.

- [ ] **4.1 Outbox:** on `CONFIRMED`, write `BookingConfirmed` to an `outbox` table **in the same transaction**. *(Concept: [transactional-outbox.md](concepts/transactional-outbox.md))*
- [ ] **4.2 Relay:** a `@Scheduled` job claims rows `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ (`bqueue`), marks `published_at`. *(Concepts: [transactional-outbox.md](concepts/transactional-outbox.md), [rabbitmq.md](concepts/rabbitmq.md))*
- [ ] **4.3 Idempotent consumer:** Notification consumes, "sends" the email (log line), dedupes via `processed_events`. *(Concept: [idempotent-consumer.md](concepts/idempotent-consumer.md))*
- [ ] **4.4 Test:** kill the relay mid-batch → restart → exactly one email per booking. `--scale notification=2` → no double-send.

**Done when:** forced redelivery and two consumers both yield exactly one email-log per booking.
**Talk track:** *"I implemented outbox + idempotent consumer and tested duplicate delivery on purpose."*

---

## Phase 5 — Break payment, watch resilience  *(field guide M5)*
**Goal:** make Booking survive Payment being down.

- [ ] **5.1 Resilience4j on Booking's payment client:** timeout 2 s, 3 retries with backoff+jitter, circuit breaker, fallback = stay PENDING + queue for later. *(Concept: [resilience-patterns.md](concepts/resilience-patterns.md))*
- [ ] **5.2 Observe:** `docker stop payment` during a load loop → timeouts → breaker opens (`/actuator/circuitbreakers`) → fast-fail into fallback → `docker start payment` → half-open → recover → queued bookings drain.

**Done when:** you can narrate the breaker opening and recovering, with numbers from `/actuator`.
**Talk track:** the [resilience interview answer](concepts/resilience-patterns.md), past tense, with numbers.

---

## Phase 6 — One trace ID across everything  *(field guide M6)*
**Goal:** debug a request that crosses five services.

- [ ] **6.1 Micrometer Tracing + Zipkin exporter** in every service; `%X{traceId}` in every log pattern. *(Concept: [observability.md](concepts/observability.md))*
- [ ] **6.2 Propagate the trace id into RabbitMQ headers** and restore it in the consumer (so the async email step joins the trace).
- [ ] **6.3 Demo:** one booking → grep all container logs by `traceId` (one story, five services) → open the Zipkin waterfall for the same request. Screenshot for the README.

**Done when:** a single traceId search returns the whole request across services; Zipkin shows the waterfall.
**Talk track:** *"Ask me how I'd debug a cross-service failure — here's the trace from my own system."*

---

## Phase 7 — Lock the doors with Keycloak  *(field guide M7)*
**Goal:** edge auth + zero trust between services. (Doubles as Spring Security / Keycloak study.)

- [ ] **7.1 Keycloak container:** realm `cinema`, a public client for users, a `booking-service` client with client-credentials. *(Concept: [security-jwt-oauth2.md](concepts/security-jwt-oauth2.md))*
- [ ] **7.2 Resource servers:** gateway + every service validate the JWT (issuer-uri). RS256, not the monolith's shared HMAC secret.
- [ ] **7.3 Service-to-service:** Booking calls Payment with a relayed token; the outbox relay uses its own client-credentials token (never put a user JWT in a queue message).
- [ ] **7.4 Demo:** no token → 401 at the gateway; user token → booking succeeds; **payment called directly with no token → 401** (zero trust, not just perimeter).

**Done when:** the three curl demos behave as above.
**Talk track:** *"Every service validates the token — defense in depth, not castle-and-moat."*

---

## Scope guard (from the field guide)
Cut ruthlessly: **no Kubernetes** (Compose is enough), **no real Stripe/PayPal** (the fake provider teaches more because you control its failures), **no gRPC implementation** (the `.proto` + comparison table in [sync-vs-async-comms.md](concepts/sync-vs-async-comms.md) is enough), **no UI** (curl + the RabbitMQ console + Zipkin).

**If time is short, protect this order:** Phase 1 → Phase 3 → Phase 4 → Phase 5. Saga, outbox, and breaker are the three demos that change how the interview answers *sound*.

## Status legend
`[ ]` not started · `[~]` in progress · `[x]` done. Update the checkboxes here as we go (see [CLAUDE.md](../CLAUDE.md) working agreements).
