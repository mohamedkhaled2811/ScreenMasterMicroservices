# Build Plan — splitting ScreenMaster into microservices

> The roadmap. Derived from the field guide's lab ([microservices-interview-field-guide.md §10](microservices-interview-field-guide.md)) and the proposed decomposition ([ARCHITECTURE_AND_SCHEMA.md §8](ARCHITECTURE_AND_SCHEMA.md)). Each step states **goal · what we build · the concept it teaches · "done when"**. Concept links point at [docs/concepts/](concepts/).
>
> **Principle:** ugly code, working demos. The value is *feeling* each microservices cost, then being able to narrate it. Don't gold-plate.

## The shape we're building toward

```
browsers / curl ─▶ GATEWAY ─┬─▶ CATALOG      (movies, genres, TMDB)        catalog-db
                            ├─▶ BOOKING      (showtimes, seats, bookings)  booking-db   ← core
                            ├─▶ PAYMENT      (fake provider, FAIL_RATE)    payment-db
                            ├─▶ NOTIFICATION (consumes BookingConfirmed)   (no db / dedupe table)
                            └─▶ IDENTITY     (Keycloak)                     keycloak-db
        all services register with ─▶ EUREKA        async events over ─▶ RABBITMQ
        traces ship to ─▶ ZIPKIN
```

Five business services + gateway + Eureka, on Docker Compose, with **database-per-service**. (We collapse the schema doc's Theater + Scheduling into Booking, since the booking transaction only needs *immutable facts* from them — see [service-decomposition-ddd.md](concepts/service-decomposition-ddd.md).)

---

## Phase 0 — Foundations ✅ done
**Goal:** understand the system and set up the workspace before writing services.

- [x] **0.1** Read both source docs; write the concept library ([docs/concepts/](concepts/)) and project guidance ([CLAUDE.md](../CLAUDE.md)).
- [x] **0.2** This build plan.
- [x] **0.3** Scaffolded the **multi-module Maven layout**: root `pom.xml` is now the **parent pom** (`<packaging>pom</packaging>`, `<modules>`, Spring Cloud BOM + Lombok in management blocks); created the six child modules `gateway`, `discovery`, `catalog`, `booking`, `payment`, `notification`, each with its own lean dependency set. Removed the placeholder root `src/`. *(Concepts: [maven-multi-module.md](concepts/maven-multi-module.md); [spring-boot-annotations.md](concepts/spring-boot-annotations.md) — the `@SpringBootApplication` per module is added in Phase 1.)*

**Done when:** ~~`./mvnw -q -pl discovery -am package` builds an empty module; the repo tree shows one folder per service.~~ ✅ Verified: `./mvnw -q -pl discovery -am package` and a full-reactor `./mvnw -q package` both pass under JDK 21; all six modules produce a jar. *(Plan: [docs/plans/2026-06-24-multi-module-scaffold.html](plans/2026-06-24-multi-module-scaffold.html).)*

> *Optional pre-work the schema doc recommends but the lab doesn't strictly need:* add **[Liquibase](concepts/liquibase.md)** and pin the schema, and convert **ordinal enums → `@Enumerated(STRING)`**. We'll fold these into each service as we carve it out, rather than touching the monolith first.

---

## Phase 1 — Skeleton & infrastructure  *(field guide M1)*
**Goal:** five services + gateway + discovery up on Compose, talking through the front door. No business logic yet.

- [x] **1.1 Eureka server** (`discovery`) — `@EnableEurekaServer` on `DiscoveryApplication`, runs on **8761**; standalone (`register-with-eureka`/`fetch-registry: false`), **self-preservation off** for the lab with a tight eviction interval. Added the `spring-boot-maven-plugin` so it builds a runnable jar; a context-load smoke test guards the wiring. **Verified:** `./mvnw -pl discovery -am package` passes under JDK 21, the jar boots, the dashboard answers `200` on 8761, and `/eureka/apps` returns an empty registry (nothing registers until 1.2). *(Concept: [service-discovery.md](concepts/service-discovery.md) — see the new "our Eureka server, two settings" note; Plan: [docs/plans/2026-06-25-eureka-discovery-server.html](plans/2026-06-25-eureka-discovery-server.html).)*
- [x] **1.2 Minimal Spring Boot apps** for `catalog` (8081), `booking` (8082), `payment` (8083), `notification` (8084). Each is a `@SpringBootApplication` Eureka **client** (starter auto-registers — no enable-annotation) exposing **`/actuator/health`**. `catalog`/`booking`/`payment` own a real Postgres (database-per-service) managed by **[Liquibase](concepts/liquibase.md)** + `ddl-auto=validate` (catalog/booking start with an empty changelog; payment ships the `payments` table). **`payment`** is the fake provider: `POST /payments` sleeps ~300 ms then returns **200 (approved) / 402 (declined)** by a typed `FAIL_RATE`, behind a `PaymentProvider` interface (real provider = add-only `@Profile("real")` later) and **idempotent** (an `Idempotency-Key` + `UNIQUE` column replays the original result — see [idempotent-consumer.md](concepts/idempotent-consumer.md)). `notification` carries web-mvc only to serve health + advertise a port (event-consumer wiring lands in Phase 4). **Verified:** full reactor `./mvnw package` is green under JDK 21; the four new `contextLoads` tests pass hermetically (H2 + Eureka off). *(Concepts: [spring-boot-annotations.md](concepts/spring-boot-annotations.md), [spring-web-annotations.md](concepts/spring-web-annotations.md), [liquibase.md](concepts/liquibase.md), [idempotent-consumer.md](concepts/idempotent-consumer.md); Plan: [docs/plans/2026-06-25-minimal-service-apps.html](plans/2026-06-25-minimal-service-apps.html). **Prereq:** `CREATE DATABASE catalog; CREATE DATABASE booking; CREATE DATABASE payment;`)*
- [x] **1.3 Gateway** (`gateway`, port **8080**) — Spring Cloud Gateway (web-mvc) is the single public front door. Declarative **YAML routes** send `/api/movies/**`→catalog, `/api/bookings/**`→booking, `/api/payments/**`→payment via `lb://` (Eureka-resolved, load-balanced). A **`StripPrefix=1`** filter on each route drops the edge-only `/api` segment so services keep bare paths (e.g. `/api/payments`→`/payments`, matching `PaymentController`) — the `/api` namespace lives only at the edge, no service changes. A Eureka **client** (no enable-annotation); added the `spring-boot-maven-plugin`; a hermetic `contextLoads` smoke test (Eureka off) parses the routes in the reactor build. Scope is **routing only** — edge auth + rate limiting are Phase 7. **Verified:** `./mvnw -pl gateway -am package` is green under JDK 21 and the jar boots on 8080; live curl shows unmatched paths → **404** while each `/api/...` route resolves through the load balancer (logs `Unable to find instance for catalog|booking|payment` when no backend is registered — proof the predicate matched and `lb://` fired). *(Concept: [api-gateway-and-bff.md](concepts/api-gateway-and-bff.md); Plan: [docs/plans/2026-06-27-gateway-routes.html](plans/2026-06-27-gateway-routes.html).)*
- [x] **1.4 Compose file** — `compose.yaml` brings the whole system up with one command: the six services + **three Postgres** (catalog-db, booking-db, **payment-db**) + RabbitMQ + Zipkin, on a dedicated bridge network where **service name = DNS name**. *(Note: the plan originally said "two Postgres" from when payment was DB-less; payment as built in 1.2 owns a `payments` table with `ddl-auto=validate`, so it gets its own DB — three total, truest to database-per-service.)* Each service has a **multi-stage Dockerfile** (Maven build stage → JRE runtime stage) built from the **repo-root context** (the reactor needs the parent pom); a root `.dockerignore` keeps the context lean. Only the edge is published to the host (gateway 8080, Eureka 8761, RabbitMQ 15672, Zipkin 9411); backend services stay internal. App services gate on their DB via `pg_isready` healthchecks + `depends_on: service_healthy`, and register their **routable Compose-network IP** (`EUREKA_INSTANCE_PREFER_IP_ADDRESS=true`) so `lb://` resolves across containers — **no `application.yml` edits** (every service already reads `${VAR:default}` env hooks). RabbitMQ + Zipkin run now but aren't wired until Phase 4 / Phase 6. **Verified:** `docker compose config` validates the topology *(live `docker compose up --build` smoke run pending — Docker Desktop daemon was down at authoring time; commands captured in the plan's Verification section).* *(Concepts: [docker-fundamentals.md](concepts/docker-fundamentals.md), [containers-and-compose.md](concepts/containers-and-compose.md); Plan: [docs/plans/2026-06-27-compose-full-stack.html](plans/2026-06-27-compose-full-stack.html).)*

**Done when:** `docker compose up --build` brings the system up; a request to the gateway reaches a service; Eureka dashboard (8761) shows all registered.
**Talk track:** *"I run a five-service system locally with isolated databases — here's the compose file."*

---

## Phase 2 — Feel the missing JOIN  *(field guide M2)*
**Goal:** experience cross-service queries and the staleness tradeoff. Booking needs movie titles it no longer owns.

- [ ] **2.1 Catalog**: real `movies`/`geners` tables (Liquibase changeset), a `GET /api/movies/{id}` returning a `MovieDto`. *(Concepts: [jpa-and-hibernate.md](concepts/jpa-and-hibernate.md), [liquibase.md](concepts/liquibase.md))*
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
