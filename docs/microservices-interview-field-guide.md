# The Microservices Field Guide

> **INTERVIEW PREP · VOLUME: MICROSERVICES** — prepared for Mohamed Khaled · ~11 h + 3 lab evenings

You flagged this as the topic you know least — and the most important one. So this volume assumes nothing, builds the mental model from first principles, and then **re-architects your own ScreenMaster cinema system as a set of services**, reusing the outbox and eventual-consistency ideas the databases volume planted. By the end, "I haven't worked with microservices" becomes "I decomposed my own system and load-tested the failure modes."

```sh
# how to use this file: same contract as the databases volume —
# read in order, build the lab, say every answer out loud.
$ curl -s http://gateway:8080/registry/services | jq '.[].name'
```

## Registry / Table of contents

*(13 services registered · all healthy)*

| key | section | after this you can answer… | time | priority |
|-----|---------|----------------------------|------|----------|
| 00 | [Why microservices](#00--why-microservices) | what problem they solve, what they cost, when a monolith wins | 45m | CORE |
| 01 | [Service boundaries (DDD)](#01--service-boundaries-domain-driven-design) | bounded contexts, aggregates, decomposing the cinema system, distributed-monolith smells | 75m | CORE |
| 02 | [Data: one DB per service](#02--data-one-database-per-service) | why shared DBs break autonomy, sagas, the outbox pattern, eventual consistency | 90m | CORE |
| 03 | [Communication](#03--communication-between-services) | sync vs async, REST vs gRPC, RabbitMQ vs Kafka, discovery, API gateway | 75m | CORE |
| 04 | [Resilience & fault tolerance](#04--resilience--fault-tolerance) | timeouts, retries done safely, circuit breakers, bulkheads, graceful degradation | 75m | CORE |
| 05 | [Security](#05--security) | edge auth with OAuth2/JWT, service-to-service trust, Keycloak's role | 60m | CORE |
| 06 | [Observability & logging](#06--observability--logging) | correlation IDs, central structured logs, distributed tracing, metrics | 45m | CORE |
| 07 | [The pattern index](#07--the-pattern-index) | every named pattern interviewers drop — gateway, saga, CQRS, strangler fig, sidecar… | 30m | CORE |
| 08 | [Running it: ops basics](#08--running-it-ops-basics) | containers, Kubernetes vocabulary, config, liveness vs readiness | 30m | LIKELY |
| 09 | [Interview Q&A bank](#09--interview-qa-bank) | 16 questions with model answers — rehearse out loud | 45m | CORE |
| 10 | [Build: split ScreenMaster](#10--build-split-screenmaster) | a 3-evening lab that turns theory into demos and war stories | 3 eve | HIGH-ROI |
| 11 | [Resources](#11--resources) | what to read in two weeks, what to save for later | — | LIKELY |
| 12 | [The 4-day plan](#12--the-4-day-plan) | how this fits your window, with the overlaps into Spring & databases | 10m | CORE |

---

## 00 · Why microservices

> **KEY 00 · WHY · ~45 min · the question behind every other question**

Every microservices interview secretly tests one thing: do you understand that this is a *tradeoff*, not an upgrade? Candidates who can name what microservices cost are instantly more credible than candidates who only recite benefits.

### Start from the monolith's real pain

A monolith — one deployable unit, one codebase, usually one database — is not a mistake; it's the right starting point for most systems (your MeetusVR backend is one, and it ships a lot of value). The pain arrives with *scale of organization*, not scale of traffic: twenty engineers in one codebase step on each other; a one-line fix to the loyalty feature requires redeploying — and re-risking — billing; the whole thing must scale as a block even if only video processing is hot; a memory leak in one module takes down everything; and the team is welded to one tech stack forever.

### What microservices actually solve

A microservice architecture splits the system into **independently deployable services, each owning one business capability and its own data**. Each property in that sentence buys something specific:

| property | what it buys | concrete example |
|----------|--------------|------------------|
| **Independent deployment** | small, frequent, low-blast-radius releases; team A ships without waiting for team B | fix a bug in notifications at 2pm without touching payment code paths |
| **Independent scaling** | scale only the hot path; cheaper infrastructure | 10 instances of booking on a blockbuster opening night, 1 of everything else |
| **Fault isolation** | one service crashing degrades a feature, not the product | recommendations die; checkout keeps selling tickets |
| **Team autonomy** | small teams own services end-to-end; fewer coordination meetings | Conway's law used on purpose: architecture mirrors team structure |
| **Tech freedom** | right tool per job, replaceable parts | a Python ML service beside Java services; rewrite one service without a big bang |

Notice the first and fourth rows are organizational. The honest one-liner for "why microservices?": **"primarily to let many teams deliver independently at speed; technical scaling benefits come second."** That framing — straight out of how Amazon and Netflix tell their own stories — is rarer in interviews than it should be.

### What they cost — say this unprompted

Everything that was a function call becomes a **network call**: it can be slow, fail halfway, or succeed without you hearing back. Data splits across services, so the easy things — joins, foreign keys, ACID transactions — stop working across boundaries and must be re-earned with sagas and eventual consistency (§02). You now need infrastructure a monolith never asked for: service discovery, gateways, central logging, tracing, per-service CI/CD. Local development means running ten containers instead of one process. Testing a business flow spans repos. This is the **distributed-systems tax**, and it's paid monthly, forever.

> **⚠️ The trap question: "so microservices are better, right?"**
>
> The expected answer is no. "For a small team or an unproven product, a **well-modularized monolith** ships faster, is easier to debug, and keeps transactions simple. I'd reach for microservices when team count, deploy contention, or divergent scaling needs make the monolith the bottleneck — and I'd extract incrementally (strangler fig, §07) rather than rewrite." Citing the 'monolith-first' position (Fowler, Newman) signals you've read the actual literature, not just job ads.

> **💡 Interview lens — use your own CV**
>
> You have a perfect 60-second story: "At MeetusVR I work in a Spring Boot monolith. The video pipeline was the one piece with wildly different scaling and failure characteristics — so it effectively got extracted, as an event-driven pipeline on S3 + EventBridge + Lambda + MediaConvert. That's the microservices logic in miniature: split where the operational characteristics diverge, keep the rest together." It shows you've *lived* the reasoning even without a microservices job title.

---

## 01 · Service boundaries (Domain-Driven Design)

> **KEY 01 · BOUNDARIES · ~75 min · DDD — where to cut**

The hardest microservices question is not "how do services talk" but "where do you cut." Wrong cuts produce the distributed monolith — all of the tax, none of the benefits. DDD is the vocabulary interviewers expect for getting the cuts right.

### The DDD ideas you actually need

**Ubiquitous language.** Within a part of the business, everyone — code included — uses the same words for the same things. When the same word means different things to different people, you've found a seam.

**Bounded context.** The central idea: a boundary inside which a model and its language are consistent. "Movie" inside *Catalog* means title, genre, runtime, poster. "Movie" inside *Booking* is just an ID and a name to print on a ticket. Forcing one giant Movie class to serve both produces the god-models monoliths drown in. **A bounded context is the natural unit of a microservice** — that sentence is the single most quotable line in this section.

**Subdomains.** The business splits into a *core* domain (your differentiator — for a cinema: booking & pricing), *supporting* subdomains (catalog management), and *generic* ones (notifications, identity — buy or use Keycloak, don't build). Spend your best engineering on the core; that prioritization argument impresses.

**Aggregates.** A cluster of objects treated as one consistency unit, modified through its root — `Booking` with its `BookingSeats` is an aggregate. The rule that connects DDD to everything in the databases volume: **one transaction modifies one aggregate**. Cross-aggregate, cross-service changes are coordinated with events instead (§02). Aggregates are also a sizing hint: a service should own whole aggregates, never split one.

### Decomposing ScreenMaster — the worked example

```text
                        ┌────────────────────┐
   browsers/mobile ───▶ │     API GATEWAY     │   auth at the edge, routing,
                        └──┬──────┬──────┬───┘   rate limiting (§05)
            sync REST      │      │      │
        ┌──────────────┐ ┌─▼──────────┐ ┌─▼────────────┐
        │   CATALOG    │ │  BOOKING   │ │   IDENTITY   │ (Keycloak —
        │ movies,      │ │ screenings,│ │ users, tokens│  generic subdomain,
        │ posters, TMDB│ │ seats,     │ └──────────────┘  don't build it)
        └──────────────┘ │ bookings   │
              ▲          └─┬────────┬─┘
              │ async       │ sync   │ async events (RabbitMQ)
              │ events      ▼        ▼
        ┌─────┴────────┐ ┌──────────┐ ┌──────────────┐
        │  (consumers) │ │ PAYMENT  │ │ NOTIFICATION │  emails, wallet passes
        └──────────────┘ │ PayPal/  │ │ consumes     │  — pure consumer,
                         │ Stripe   │ │ BookingConfirmed │  no one calls it
                         └──────────┘ └──────────────┘
```

Narrate the reasoning, not just the boxes: *Catalog* is read-heavy, cache-friendly, tolerates staleness — separate scaling profile. *Booking* is the core domain: contended writes, strict consistency on seats (the databases volume's whole §04). *Payment* wraps external providers — isolate the slow, flaky outside world behind one service. *Notification* is fire-and-forget — pure event consumer. *Identity* is generic — delegate to Keycloak. Five services, each with a one-sentence justification: that's exactly the answer format a "design a cinema system with microservices" question wants.

### How to find boundaries in general

Decompose by **business capability** (things the business does: "manage catalog", "take bookings", "collect payment") or equivalently by subdomain — and validate each candidate boundary with three tests: could a small team own it end-to-end? Can it change and deploy without forcing changes elsewhere? Does it own all the data it needs for its core job? If a "service" fails the second test, it's a module wearing a service costume.

> **⚠️ Anti-patterns to name-drop**
>
> **Entity services** — cutting by noun (UserService, MovieService, SeatService) yields anemic CRUD wrappers where every business flow spans five services. Cut by capability, not by table. **Nanoservices** — so fine-grained that the network outweighs the logic. **Distributed monolith** — the failure mode to define crisply: services that must deploy together, share a database, or call each other in deep synchronous chains. Smells: a release calendar coordinating "the services", one schema change breaking three repos, latency that's a sum of six hops. The fix is usually merging services back or breaking the data coupling, and saying "merging is a valid refactor" is a senior move.

> **💡 Interview lens**
>
> "How would you split a monolith?" wants process, not heroics: identify bounded contexts from the language the business uses → pick the seam with the best value-to-risk ratio (often something event-friendly like notifications, or your video pipeline) → extract it behind an interface while the monolith keeps running (strangler fig, §07) → repeat. One service at a time, never a rewrite.

---

## 02 · Data: one database per service

> **KEY 02 · DATA · ~90 min · the heart of the topic**

This is where microservices stop being "small REST apps" and become a genuinely different discipline. Every hard question — consistency, sagas, the outbox — lives here. It's also where your databases volume pays off directly.

### Shared database vs database per service

The tempting shortcut is many services, one database. It feels pragmatic and it quietly destroys the architecture: the schema becomes a shared contract, so one table change ripples through every service (lockstep deploys — the distributed monolith); services bypass each other's logic by writing to each other's tables; one service's runaway query starves everyone; and you can never change the storage technology for one capability. **Database per service** means each service's data is private — others get to it only through the service's API or events. "Database" here can mean a separate schema or separate server; the rule is *no shared tables, no cross-service joins, no exceptions*.

Saying the cost out loud is what earns trust: you have just given up cross-service `JOIN`s, cross-service foreign keys, and cross-service ACID transactions. The rest of this section is how those get re-earned.

### Re-earning queries: composition and read models

"Show my bookings with movie titles" now spans Booking and Catalog. Two answers, in escalating order: **API composition** — the gateway or a BFF calls both services and merges in memory; fine for simple pages, awkward for filtering/sorting across services. **CQRS read models** — Command Query Responsibility Segregation: services publish events, and a consumer maintains a denormalized, query-optimized view (e.g., a `booking_history` table or Elasticsearch index containing booking + movie title + poster). Writes go to the owning services; heavy reads hit the view. Cost: the view is *eventually consistent* — it lags by however long events take to arrive. Note the rhyme with the databases volume: this is denormalization, applied across services instead of across tables.

### Re-earning transactions: sagas

Confirming a booking touches two services: Booking (reserve seats) and Payment (charge). There is no `BEGIN … COMMIT` across two databases. The classical answer, **two-phase commit**, is avoided in microservices and you should know why in one breath: a coordinator asks everyone to prepare, then commit — but participants sit *blocked holding locks* while they wait, and if the coordinator dies they're stuck; availability collapses exactly when things go wrong, and most brokers/HTTP services don't speak it anyway.

Instead: a **saga** — a sequence of *local* transactions, one per service, where each step's failure triggers **compensating transactions** that semantically undo the completed steps (in reverse order). Not a rollback — the money was really captured, so the compensation is a real refund.

```text
BOOKING SAGA — happy path                 failure at step 2
1. Booking: create PENDING, hold seats    1. Booking: create PENDING, hold seats
2. Payment: charge card        ──OK──▶    2. Payment: charge fails  ──✗──▶
3. Booking: mark CONFIRMED                C1. Booking: release seats,
4. Notification: send tickets                  mark CANCELLED  (compensation)
```

Two coordination styles — know the tradeoff table cold:

| | choreography (events) | orchestration (coordinator) |
|---|---|---|
| mechanism | each service reacts to the previous service's event — Booking emits `SeatsHeld`, Payment listens, emits `PaymentSucceeded`… | a saga orchestrator (e.g., in Booking) explicitly commands each step and decides what's next |
| coupling | loose — no one knows the whole flow | participants are simple; the orchestrator knows the flow |
| visibility | the flow exists only implicitly; "where is booking 42 stuck?" is hard | flow is explicit code + state machine; easy to reason about and monitor |
| risk | cyclic event spaghetti as steps grow | orchestrator drifting into a god-service |
| use when | 2–3 steps, naturally event-shaped | 4+ steps, compensations, business-critical flows (payments!) |

### The dual-write problem → the outbox pattern

Inside one saga step lurks the sneakiest bug in event-driven systems. Booking must (a) commit `status = CONFIRMED` to Postgres and (b) publish `BookingConfirmed` to RabbitMQ. Two systems, no shared transaction: crash between them and either the DB says confirmed but no email ever sends, or (other order) an event announces a booking that rolled back. That's the **dual write**.

The fix — which you already met as milestone M6 of the databases volume — is the **transactional outbox**: write the event into an `outbox` table *in the same local transaction* as the business change; a separate relay then delivers it to the broker.

```sql
BEGIN;
UPDATE bookings SET status = 'CONFIRMED' WHERE id = 42;
INSERT INTO outbox (id, aggregate_id, event_type, payload, created_at)
VALUES (gen_random_uuid(), 42, 'BookingConfirmed', '{"bookingId":42,...}', now());
COMMIT;   -- atomic: both rows or neither

-- relay (scheduled job or, in production, CDC e.g. Debezium tailing the WAL):
SELECT * FROM outbox WHERE published_at IS NULL
ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100;
-- publish to RabbitMQ → mark published_at = now()
```

Delivery is now **at-least-once** (the relay may crash after publishing, before marking) — which forces the final piece:

### Idempotent consumers

Every consumer must survive receiving the same event twice. The standard mechanics: events carry a unique ID; the consumer records processed IDs and skips repeats — atomically with its own work:

```sql
BEGIN;
INSERT INTO processed_events (event_id) VALUES ($1)
ON CONFLICT DO NOTHING;            -- second delivery inserts 0 rows
-- only if the insert actually inserted: do the work (send the email,
-- update the read model). Same transaction → exactly-once *effect*.
COMMIT;
```

The aphorism worth quoting: *exactly-once delivery is impossible; exactly-once processing is an application-level achievement* — at-least-once delivery plus idempotency.

> **⚠️ Eventual consistency — answer the scary version**
>
> "So the user sees stale data?!" — yes, briefly, and that's a business decision, not a bug: the email arriving 2 seconds after payment is invisible; the seat map being 2 seconds stale is fine because the *seat-uniqueness constraint inside Booking* remains strictly consistent — the one place that must be. Strong consistency *inside* a service boundary (one DB, real transactions), eventual consistency *between* services. That sentence is the whole data story in miniature; deliver it slowly.

---

## 03 · Communication between services

> **KEY 03 · COMMS · ~75 min · REST, gRPC, messaging, discovery, gateway**

The interview question is rarely "what is gRPC" — it's "*when* would you pick each option." Lead every answer with the sync/async decision; the protocol is the second-order choice.

### The first fork: synchronous or asynchronous?

**Synchronous** (request/response — REST, gRPC): the caller needs the answer *now* to proceed. Booking → Payment is sync: you can't confirm without knowing the charge result. Costs: temporal coupling (both must be up), latency adds up along chains, failures cascade (hence all of §04). **Asynchronous** (messages/events via RabbitMQ, Kafka): the caller doesn't need an immediate answer. Booking → Notification is async: tickets can be emailed seconds later. Buys: sender and receiver decoupled in time, natural buffering under load spikes, one event can fan out to many consumers. Costs: eventual consistency, harder debugging, broker to operate. The design heuristic that sounds senior: **default to async between services; go sync only where the user's request genuinely cannot complete without the answer.** Deep synchronous chains (A→B→C→D) are the smell to call out — availability multiplies down: four 99.9% services in a chain ≈ 99.6%.

### REST vs gRPC

| | REST + JSON | gRPC + Protobuf |
|---|---|---|
| contract | OpenAPI (optional, drifts) | `.proto` file is the source of truth; client/server code generated |
| wire | text JSON over HTTP/1.1 — human-readable, debuggable with curl | binary over HTTP/2 — smaller, faster, multiplexed connections |
| streaming | awkward (SSE/WebSocket bolt-ons) | first-class: server-, client-, and bidirectional streaming |
| deadlines | roll your own timeouts | deadline propagation built into the protocol |
| reach | every client on earth; browsers; caches/CDNs understand it | browsers need grpc-web; mostly an internal protocol |
| pick it for | public APIs, simple CRUD between services, maximum interoperability | high-QPS internal calls, low latency, polyglot codegen, streaming |

```proto
// payment.proto — the contract IS this file
syntax = "proto3";

service PaymentService {
  rpc Charge (ChargeRequest) returns (ChargeResult);
}

message ChargeRequest {
  int64  booking_id = 1;     // field numbers, not names, go on the wire —
  string currency   = 2;     // that's why renaming is safe but renumbering
  int64  amount_minor = 3;   // breaks compatibility. add fields, never reuse numbers.
}
message ChargeResult { bool approved = 1; string provider_ref = 2; }
```

The follow-up "how do you evolve APIs without breaking consumers?" applies to both: additive changes only (new optional fields), never repurpose a field, version when you must (`/v2` path for REST; new proto messages for gRPC), and ideally verify with **consumer-driven contract tests** (Pact) in CI — name-dropping that answers the question before it's asked.

### Messaging: RabbitMQ vs Kafka — you know half of this already

You've shipped RabbitMQ; frame the comparison from there. **RabbitMQ** is a *smart broker*: exchanges route messages into queues, consumers compete for work, messages are acked and gone. Ideal for task queues and complex routing — your ScreenMaster email flow. **Kafka** is a *distributed append-only log*: events are retained (hours to forever) in partitioned topics; consumer groups track their own offsets, so the same stream feeds many independent consumers, can be *replayed* (rebuild a read model from history!), and ordering is guaranteed per partition (choose the partition key well — e.g., `bookingId` so one booking's events stay ordered). Heuristic: **work distribution and routing → RabbitMQ; event streaming, replay, high-throughput fan-out → Kafka.** Also be precise with terms: a **command** ("ChargePayment") is addressed to one handler and expects action; an **event** ("BookingConfirmed") states a fact, sender doesn't know or care who listens. Choreographed sagas run on events; orchestrated ones send commands.

### Discovery, load balancing, and the gateway

Instances come and go, so hardcoded URLs die first. **Client-side discovery**: services register with a registry (Eureka, Consul); callers fetch the instance list and balance themselves (Spring Cloud LoadBalancer). **Server-side discovery**: callers hit a stable name and infrastructure routes — which is exactly what Kubernetes gives you for free: a `Service` is a stable DNS name (`http://payment`) load-balancing over healthy pods. The honest modern note: *on Kubernetes you usually don't need Eureka* — saying so shows your knowledge isn't frozen in 2017.

The **API gateway** is the single front door for external clients: routing, TLS termination, authentication (§05), rate limiting, and response shaping live there — once, instead of in every service. A **BFF** (backend-for-frontend) is a gateway variant: one tailored edge API per client type (mobile vs web) so neither gets a lowest-common-denominator API. Internal service-to-service calls do *not* go back out through the gateway — they go direct (via discovery) or through a service mesh (§07).

```yaml
# Spring Cloud Gateway — routes in 8 lines (you'll write this in the lab)
spring:
  cloud:
    gateway:
      routes:
        - id: catalog
          uri: http://catalog:8081        # docker-compose / k8s DNS name
          predicates: [ "Path=/api/movies/**" ]
        - id: booking
          uri: http://booking:8082
          predicates: [ "Path=/api/bookings/**" ]
```

---

## 04 · Resilience & fault tolerance

> **KEY 04 · RESILIENCE · ~75 min · designing for partial failure**

The defining fact of distributed systems: *something is always partially broken*. A service that assumes its dependencies are up is the service that takes the platform down. This section is a toolbox, ordered from "always" to "when needed."

### Timeouts — the non-negotiable baseline

Every network call gets an explicit timeout, no exceptions — both **connect** (can I reach you? short, ~1s) and **read** (how long may the answer take? sized from the dependency's real p99, not a guess). Why it's first: without timeouts, a slow dependency makes *your* threads pile up waiting, your thread pool and connection pool exhaust, and now *you're* down too — that's a **cascading failure**, and "slow" is more dangerous than "down" because failure detection takes the full wait. Bonus phrase: in long call chains, propagate a *deadline* ("this whole request has 2s left") rather than letting each hop independently wait 5s — gRPC does this natively.

### Retries — powerful and dangerous

Transient failures (a blip, a restarting pod) deserve a retry; real failures don't. The safety checklist that turns "I'd retry" into a senior answer: **(1) only retry idempotent operations** — a timed-out charge may have succeeded; retrying double-charges unless the operation carries an **idempotency key** (you've seen Stripe's `Idempotency-Key` header — say so!); **(2) exponential backoff with jitter** — wait 100ms, 200ms, 400ms ± randomness, because synchronized retries from a thousand clients hammer the recovering service in waves (a *retry storm* / thundering herd); **(3) cap attempts** (2–3) and budget retries globally; **(4) never retry on business errors** — 400/422 means the request is wrong; only 5xx/timeouts/connection failures are retryable. And know the amplification math: if A retries 3× and B (which A calls) retries 3×, a single user click can become 9 calls to C — retry at one layer, not every layer.

### Circuit breaker — fail fast, recover gracefully

When a dependency is properly down, even fast-failing retries waste resources and delay user feedback. A circuit breaker watches the failure rate and, past a threshold, **stops calling entirely** for a cooldown:

```text
            failure rate over sliding window > 50%
   ┌────────┐ ───────────────────────────────────▶ ┌────────┐
   │ CLOSED │            calls fail fast,          │  OPEN  │
   │ normal │ ◀──────────  no network I/O          │        │
   └────────┘  trial calls                         └───┬────┘
        ▲      succeed                                 │ wait duration elapses
        │                 ┌───────────┐               ▼
        └──────────────── │ HALF-OPEN │ ◀──────────────
            trial fails → │ few trial │
            back to OPEN  │   calls   │
                          └───────────┘
```

Why failing fast is a feature: users get an instant fallback instead of a 30s hang; your threads are freed; and the struggling dependency gets breathing room to recover instead of a pile-on. In the Spring world the library is **Resilience4j** (Hystrix's successor — mention the succession):

```java
@Service
public class PaymentClient {

    @CircuitBreaker(name = "payment", fallbackMethod = "paymentUnavailable")
    @Retry(name = "payment")            // Retry wraps the breaker by default —
    @TimeLimiter(name = "payment")      // each attempt is counted by the breaker
    public CompletableFuture<ChargeResult> charge(ChargeRequest req) {
        return CompletableFuture.supplyAsync(() -> restClient.post()
                .uri("http://payment/api/charges")
                .body(req).retrieve().body(ChargeResult.class));
    }

    private CompletableFuture<ChargeResult> paymentUnavailable(ChargeRequest req, Throwable t) {
        // degrade, don't die: keep the booking PENDING, queue a retry, tell the
        // user "payment is taking longer than usual — we'll email your tickets."
        return CompletableFuture.completedFuture(ChargeResult.pendingAsync(req.bookingId()));
    }
}
```

```yaml
resilience4j:
  circuitbreaker:
    instances:
      payment:
        slidingWindowSize: 20            # judge over the last 20 calls
        failureRateThreshold: 50         # open at 50% failures
        waitDurationInOpenState: 10s     # cooldown before HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      payment: { maxAttempts: 3, waitDuration: 200ms, enableExponentialBackoff: true }
  timelimiter:
    instances:
      payment: { timeoutDuration: 2s }
```

### Bulkheads, rate limits, and degradation

**Bulkhead** (named for ship compartments): give each dependency its own bounded thread/connection pool so a flood toward Payment can't drown the threads Catalog needs — one compartment floods, the ship floats. **Rate limiting** at the gateway protects you from clients; **load shedding** (rejecting excess work early with 429/503) protects you from yourself — rejecting 10% of requests fast beats serving 100% slowly until collapse. And design explicit **graceful degradation**: recommendations down → show generic popular movies; seat-map service slow → serve the 5-second-old cached map with a "refreshing…" hint. Product keeps working, dimmer.

> **💡 Interview lens — the scenario you should pray they ask**
>
> "Payment service goes down during checkout — what happens?" Walk the layers in order: the call **times out** (2s, not 30) → **retries** with backoff fire only if it might be transient, with an idempotency key so no double charge → failures trip the **circuit breaker**, so subsequent users fail fast into the **fallback**: booking saved as PENDING-PAYMENT with seats held briefly, user told payment will complete asynchronously → when Payment recovers, breaker half-opens and queued work drains; if it can't complete in time, the **saga compensation** releases the seats and notifies the user. Six concepts, one coherent story — and after the lab (§10, M5) you'll have literally watched it happen.

---

## 05 · Security

> **KEY 05 · SECURITY · ~60 min · edge auth + service-to-service trust**

Two distinct problems that interviewers love to see kept distinct: (1) authenticating *users* at the edge, and (2) authenticating *services to each other* inside. This section overlaps deliberately with your Spring/Keycloak volume — same concepts, architecture-level view.

### Edge: OAuth2/OIDC + JWT

The standard flow: the client signs in against the identity provider — **Keycloak** in your stack — via the OAuth2 **authorization code flow (with PKCE)**; OIDC layers identity on top, returning an ID token alongside the **access token** (a JWT). The client sends `Authorization: Bearer <jwt>`; the **gateway validates the JWT first** — signature against Keycloak's published public keys (the **JWKS** endpoint), plus expiry, issuer, and audience — and rejects garbage before it touches the cluster.

The "why JWT for microservices" answer: validation is **stateless and local**. Any service can verify the signature with cached public keys — no per-request network call to a session store or to Keycloak. The token itself carries identity and authorization claims (`sub`, roles, scopes). The cost, which you must volunteer: **a JWT cannot be revoked** — it's valid until `exp`. Mitigation: short-lived access tokens (5–15 min) paired with refresh tokens that *can* be revoked at Keycloak. The alternative — opaque tokens checked via the **introspection endpoint** — gives instant revocation at the price of a network hop per request; pick per sensitivity, and knowing the dial exists is the point.

### Inside: zero trust between services

Once past the gateway, do services just trust each other? The modern answer is no — **zero trust**: the internal network is not a security boundary (one compromised pod shouldn't get the run of the cluster). Concretely, each downstream service is a **resource server** that validates the JWT again (in Spring: `spring-boot-starter-oauth2-resource-server` + the issuer URI — your Spring volume's territory) and enforces its own authorization from claims. Never trust identity headers like `X-User-Id` coming from outside; the gateway strips and re-sets such headers if used at all.

For calls where *the service itself* is the actor — the outbox relay, a nightly job, Notification calling Catalog for poster URLs — there is no user JWT. The standard is the OAuth2 **client credentials grant**: the service authenticates to Keycloak with its own client ID/secret and receives a machine token scoped to what it may do:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          notification-m2m:
            client-id: notification-service
            client-secret: ${KC_SECRET}        # from env/secret store, never git
            authorization-grant-type: client_credentials
            scope: catalog.read
        provider:
          notification-m2m:
            token-uri: http://keycloak:8085/realms/cinema/protocol/openid-connect/token
```

When a user's request fans out (Booking calls Payment *on behalf of* user 42), the simple approach is **token relay** — forward the user's JWT so Payment sees the real principal. Its limits are worth naming: the token may expire mid-flow (especially across async boundaries — never stuff a user JWT into a queue message; store the user ID in the event and act with a machine token instead), and it grants the callee everything the user could do. The refined tool is **OAuth2 Token Exchange (RFC 8693)** — trading the user token for a narrower one per hop; name-drop level is enough.

### Transport: mTLS and the mesh

JWTs authenticate the *request*; **mutual TLS** authenticates and encrypts the *connection* — both sides present certificates, so a rogue process can't even open a socket to Payment, and traffic can't be sniffed in-cluster. Managing certificates per service by hand is misery, which is the actual selling point of a **service mesh** (Istio, Linkerd): a sidecar proxy beside each pod transparently upgrades all traffic to mTLS and handles retries/timeouts/telemetry without touching application code (§07). Round out with hygiene one-liners: secrets in a vault or K8s Secrets (never in git — your `${KC_SECRET}` above), network policies to whitelist who may talk to whom, and least-privilege scopes per client.

> **⚠️ The trap: "the gateway checks the token, so internal services can skip auth"**
>
> That's perimeter ("castle-and-moat") thinking, and the expected pushback is: defense in depth — every service validates the JWT (it's cheap and local), service identities use client-credentials tokens, and ideally mTLS underneath. One SSRF or one compromised pod shouldn't equal total compromise.

---

## 06 · Observability & logging

> **KEY 06 · OBSERVE · ~45 min · logging, tracing, metrics**

In a monolith, debugging is reading one log file. In microservices, one user click produces log lines in six containers that restart and vanish. The interview question is always some form of: "a request failed somewhere across your services — how do you find it?"

### Logging that survives distribution

Three upgrades turn monolith logging into microservice logging. **(1) Structured logs:** emit JSON, not prose — `{"level":"ERROR","service":"payment","bookingId":42,…}` — so logs are queryable fields, not grep targets. **(2) Central aggregation:** containers are ephemeral, so logs ship to one searchable store — the ELK stack (Elasticsearch + Logstash/Fluentd + Kibana), Grafana Loki, or CloudWatch in your AWS world. Apps log to stdout; collectors do the shipping (a 12-factor principle: logs are event streams, not files you manage). **(3) Correlation:** the piece everything depends on, below.

### Correlation IDs — the answer to "how do you follow a request?"

At the edge, the gateway stamps each request with a unique **trace ID**; every service puts it in its logging context and **propagates it on every outgoing call and every published event**. Now one search — `traceId:7f3a…` — returns the request's complete story across all services in order. The W3C standard header is `traceparent`; in Spring Boot 3 this is **Micrometer Tracing** (successor to Spring Cloud Sleuth — knowing the succession dates your knowledge correctly), which auto-instruments incoming/outgoing HTTP and drops `traceId/spanId` into the SLF4J MDC:

```text
# logback pattern:  %d %-5level [${spring.application.name},%X{traceId},%X{spanId}] %msg%n

# the same trace ID, three services, one story:
10:01:22.310 INFO  [gateway,7f3ab9e2c1d44a02,a1] → POST /api/bookings
10:01:22.317 INFO  [booking,7f3ab9e2c1d44a02,b4] seats held bookingId=42
10:01:22.391 ERROR [payment,7f3ab9e2c1d44a02,c9] charge declined: insufficient_funds
10:01:22.402 INFO  [booking,7f3ab9e2c1d44a02,b4] compensating: seats released
```

### Distributed tracing — the same idea, visualized

A **trace** is the whole request; each timed unit of work (an HTTP call, a DB query) is a **span** with a parent — together a tree. Export spans (via **OpenTelemetry**, the vendor-neutral standard) to **Zipkin** or **Jaeger** and you get a waterfall: exactly where the 900ms went, which hop failed, what ran in parallel. Tracing answers *where is it slow/broken*; logs answer *why*. Production note that earns a nod: tracing is usually *sampled* (e.g., 10% of requests) to control overhead.

### Metrics & alerting

Logs and traces explain individual requests; **metrics** watch the aggregate. Spring Boot's Actuator + **Micrometer** expose them; **Prometheus** scrapes; **Grafana** dashboards them. Per service, track the **RED method**: *Rate* (req/s), *Errors* (failure %), *Duration* (latency percentiles — quote **p95/p99**, never averages: an average hides the one-in-twenty 3-second request). Two production habits worth saying: alert on *symptoms* users feel (error rate, p99) rather than causes (CPU), and watch your resilience tooling itself — a circuit breaker's state is a metric; a breaker stuck open *is* the incident. Health endpoints (`/actuator/health`) close the loop by feeding orchestrator probes (§08).

---

## 07 · The pattern index

> **KEY 07 · PATTERNS · ~30 min · the named-pattern index**

You asked "what patterns are used in microservices" — this is the canonical list (Chris Richardson's catalog at microservices.io is the industry reference). Most are taught in depth above; this table is your revision sheet: each pattern as problem → solution in one breath.

| pattern | problem → solution | depth |
|---------|--------------------|-------|
| **API Gateway** | clients shouldn't know N services or re-implement auth → one front door for routing, auth, rate limiting | §03 |
| **Backend-for-Frontend** | web and mobile need different shapes → one tailored gateway per client type | §03 |
| **Service Discovery** | instances come and go → registry (Eureka/Consul) or platform DNS (K8s Service) | §03 |
| **Database per Service** | shared schema couples deploys → each service owns its data; access via API/events only | §02 |
| **Saga** | no ACID across services → sequence of local transactions + compensations (choreography/orchestration) | §02 |
| **Transactional Outbox** | dual write (DB + broker) can half-fail → event written in the same local transaction, relayed after | §02 |
| **Idempotent Consumer** | at-least-once delivery duplicates messages → dedupe by event ID, atomically with the work | §02 |
| **CQRS** | cross-service queries are hard → event-built read models, separate from the write side | §02 |
| **Event Sourcing** | state-as-rows loses history → store the events as truth, derive state by replay. Powerful, niche; pairs with CQRS; don't claim it lightly | here |
| **Circuit Breaker** | hammering a dead dependency spreads failure → trip open, fail fast, probe to recover | §04 |
| **Bulkhead** | one bad dependency drains all threads → isolated pools per dependency | §04 |
| **Retry + Backoff + Jitter** | transient blips fail requests → bounded, idempotent-only, desynchronized retries | §04 |
| **Strangler Fig** | big-bang rewrites fail → route at a façade, extract capability by capability until the monolith withers | below |
| **Anti-Corruption Layer** | a legacy/external model would leak into your clean one → a translation layer at the boundary | below |
| **Sidecar / Service Mesh** | mTLS, retries, telemetry re-implemented per service → a proxy per pod (Envoy) handles them; Istio/Linkerd manage the fleet | §05 |
| **Externalized Config** | same image, many environments → config from env/config server, not baked in | §08 |

### The two worth sixty extra seconds

**Strangler fig** — named for the vine that grows around a tree until the tree is gone — is *the* answer to "how would you migrate a monolith to microservices?" Put a routing façade (the gateway) in front of the monolith; pick one capability; build it as a service; flip its routes; repeat. The monolith shrinks release by release, the business never stops, and every step is reversible. Pair it with an **anti-corruption layer** when the new service must still talk to the monolith: a thin translator so the legacy model's quirks don't infect the new domain model. You can ground this in your own work: extracting MeetusVR's video processing into the AWS event pipeline is a strangler-style extraction — the monolith kept running while the capability moved out.

---

## 08 · Running it: ops basics

> **KEY 08 · OPS · ~30 min · enough Kubernetes to be dangerous**

You won't be interviewed as a platform engineer, but microservices answers leak ops vocabulary constantly. This is the minimum fluent set — you already have Docker and Compose, so this is mostly naming things you half-know.

### From Compose to Kubernetes — the vocabulary map

Docker Compose runs your lab on one machine; **Kubernetes** is the same idea across a fleet, declaratively: you describe desired state, controllers make reality match. The six words to use correctly: a **Pod** is the smallest unit (your container + maybe a sidecar); a **Deployment** says "keep 3 replicas of this pod spec running" and handles rolling updates; a **Service** is the stable DNS name + load balancer over those pods (the server-side discovery from §03); an **Ingress** routes external HTTP in; a **ConfigMap**/**Secret** injects configuration; **HPA** (horizontal pod autoscaler) adds replicas under load — the "scale only the hot service" promise from §00, delivered.

### Liveness vs readiness — a favorite quick question

Both are probes against your health endpoints, with different consequences. **Liveness**: "is this process beyond saving?" — fail it and the pod is *restarted* (right for deadlocks). **Readiness**: "can it take traffic right now?" — fail it and the pod is *removed from the Service's rotation*, not killed (right while warming up, or while a dependency is briefly down). The classic mistake to name: wiring a dependency check (database reachable?) into *liveness* — then a DB blip makes Kubernetes restart-loop perfectly healthy pods, turning an incident into an outage. Dependency checks belong in readiness.

### Config, releases, and the 12-factor habit

One image, promoted unchanged through environments; behavior differences come from **externalized config** (env vars, ConfigMaps, or Spring Cloud Config) — that's the heart of [12-factor](https://12factor.net), worth a skim for the vocabulary alone (stateless processes, logs as streams, backing services as attached resources). Releases per service are where microservices pay off: small diffs, **rolling updates** by default, **canary** (send 5% of traffic to the new version, watch the RED metrics, then ramp) when risk is higher. CI/CD pipelines are per-repo/per-service: build → test (including contract tests, §03) → image → deploy. You don't need depth here — accurate vocabulary plus "I'd start with Compose locally and the platform team's K8s templates in prod" is exactly calibrated honesty.

---

## 09 · Interview Q&A bank

> **KEY 09 · Q&A · ~45 min · rehearse out loud, twice**

Sixteen questions spanning the whole volume. Same method as the databases bank: answer out loud first, then open and compare. The ones that feel wobbly today are tomorrow's first rep.

**Q01 · Why microservices? What problems do they solve?**

Primarily organizational: independent deployability lets many teams ship without coordinating releases; small blast radius per change. Then technical: per-service scaling, fault isolation, tech freedom. Cost: the distributed-systems tax — network failure modes, no cross-service transactions, heavy infra (discovery, tracing, per-service CI/CD). So they pay off when team count and deploy contention bite, not by default.

**Q02 · When would you NOT use microservices?**

Small team, early product, unclear domain boundaries — a well-modularized monolith ships faster, debugs easier, keeps ACID. Wrong boundaries are far cheaper to fix inside one codebase. Monolith-first, extract when evidence demands (deploy contention, divergent scaling, team growth), via strangler fig rather than rewrite.

**Q03 · How do you decide service boundaries?**

By business capability, using DDD: find bounded contexts — where the language and model stay consistent ("Movie" means different things in Catalog vs Booking) — and make each candidate service own whole aggregates and all the data for its job. Validate: can a small team own it end-to-end, and can it deploy without forcing changes elsewhere? Avoid entity-services (cutting by noun/table) — they make every flow span five services.

**Q04 · What is a distributed monolith?**

Microservices in shape, monolith in behavior: services that must deploy together, share a database, or sit in deep synchronous call chains. Smells: coordinated release calendars, one schema change breaking several repos, latency = sum of hops, "we can't test service A without B, C, D up." Fix: break the data coupling (own your data, communicate via events) — or honestly merge services back; merging is a valid refactor.

**Q05 · Database per service or shared database — and why?**

Per service. A shared DB makes the schema a public contract: every change ripples across services, deploys lock together, services bypass each other's invariants by writing tables directly, and one service's load starves the rest. Owning data privately restores autonomy, at a stated price: no cross-service joins (→ API composition or CQRS read models) and no cross-service ACID (→ sagas). The price is the architecture working as intended.

**Q06 · How do you keep data consistent across services without distributed transactions?**

Sagas: a sequence of local transactions, each followed by a published event/command; on failure, compensating transactions semantically undo completed steps in reverse (a refund, not a rollback). Choreography (pure events, loose, good for 2–3 steps) vs orchestration (explicit coordinator, visible state machine — my pick for payment-critical flows). The result is eventual consistency between services, with strict consistency preserved inside each service where it matters — seat uniqueness stays a hard DB constraint inside Booking.

**Q07 · Why not two-phase commit?**

2PC is blocking: participants hold locks while waiting for the coordinator, and if the coordinator dies they're stuck in-doubt — availability collapses exactly during failures, the opposite of what microservices want. It also doesn't span typical heterogeneous parts (HTTP services, message brokers). Hence sagas: trade atomicity for availability and handle failure with compensation.

**Q08 · What's the dual-write problem and how does the outbox pattern fix it?**

Dual write = updating the database and publishing to the broker as two separate operations; a crash between them leaves them disagreeing (confirmed booking, no event — or event for a rolled-back booking). Outbox: write the event into an outbox table in the same local transaction as the business change — atomic by construction — then a relay (poller, or CDC like Debezium tailing the WAL) publishes it. Delivery becomes at-least-once, so consumers must be idempotent: dedupe by event ID, recorded atomically with the consumer's own work.

**Q09 · REST vs gRPC vs messaging — how do you choose?**

First fork: does the caller need the answer to proceed? No → async messaging (decoupled in time, buffers spikes, fans out); yes → sync. Within sync: REST for public APIs and interoperability (human-readable, cacheable, curl-able); gRPC for internal high-throughput/low-latency calls — protobuf contract, HTTP/2 multiplexing, codegen, native streaming and deadlines. My default: async between services wherever the UX allows; sync only on the critical request path (booking → payment).

**Q10 · RabbitMQ vs Kafka?**

RabbitMQ: smart broker — exchanges route into queues, competing consumers, ack-and-gone. Best for task distribution and rich routing; it's what I used in my cinema project for email jobs. Kafka: distributed append-only log — retained, partitioned topics; consumer groups track offsets, so many independent consumers read the same stream and can replay history (rebuild a read model). Ordering per partition via the partition key. Streaming, fan-out, replay → Kafka; work queues and routing → RabbitMQ.

**Q11 · Explain the circuit breaker. Why is failing fast good?**

It tracks failures over a sliding window; past a threshold it opens — calls fail immediately without I/O — then after a cooldown goes half-open, letting trial calls decide whether to close or re-open. Failing fast frees my threads (no pile-up behind a 30s timeout → no cascading failure), gives users an instant fallback instead of a hang, and stops the pile-on so the sick dependency can recover. In Spring: Resilience4j, Hystrix's successor.

**Q12 · What can go wrong with retries, and how do you make an operation safe to retry?**

Risks: retrying non-idempotent ops (double charge), retry storms hammering a recovering service, and amplification when every layer retries (3×3 = 9 calls downstream). Discipline: idempotent operations only, exponential backoff with jitter, low attempt caps, retry only transient failures (timeouts/5xx, never 4xx), retry at one layer. Idempotency by design: client-generated idempotency key stored unique server-side — replay returns the original result (Stripe's Idempotency-Key header is the canonical example); or natural idempotency like unique constraints — my seat-booking insert can be retried safely because the UNIQUE(screening, seat) makes the second attempt a clean no-op failure.

**Q13 · How do you secure service-to-service communication?**

Zero trust — don't treat the internal network as a boundary. Request level: every service validates JWTs (signature via the IdP's JWKS, expiry, audience); for machine-initiated calls, OAuth2 client-credentials tokens with least-privilege scopes from Keycloak; user-context fan-out via token relay, or token exchange (RFC 8693) for narrowing. Transport level: mTLS so both ends prove identity and traffic is encrypted — in practice delegated to a service mesh sidecar (Istio/Linkerd). Plus hygiene: secrets in a vault, network policies, gateway strips spoofable identity headers.

**Q14 · Why JWTs at the edge — and what's their main weakness?**

Stateless local validation: any service verifies the signature with cached public keys — no session store, no per-request IdP call — and claims carry identity/roles. Weakness: no revocation; a stolen token works until exp. Mitigate with short-lived access tokens + revocable refresh tokens; where instant revocation matters more than the extra hop, opaque tokens with introspection. Also: validate audience/issuer, and never put secrets in the payload — it's only base64.

**Q15 · A request failed somewhere across six services. How do you find it?**

Correlation: the gateway stamps a trace ID (W3C traceparent), every service logs it (Micrometer Tracing puts it in the MDC) and propagates it on calls and events; structured JSON logs ship to a central store (ELK/Loki/CloudWatch), so one traceId search returns the whole story in order. For latency questions, distributed tracing (OpenTelemetry → Zipkin/Jaeger) shows the span waterfall — which hop burned the 900ms. Metrics (RED per service, Prometheus/Grafana) tell me something is wrong; traces tell me where; logs tell me why.

**Q16 · How would you migrate a monolith to microservices?**

Strangler fig, never big-bang: put a routing façade (gateway) in front; pick the first seam by value-to-risk — something loosely coupled and event-friendly (notifications, media processing); extract it with its own data, an anti-corruption layer where it still touches the monolith; flip routes, measure, repeat. Each step reversible, business never stops. I'd add: at MeetusVR our video pipeline effectively followed this — extracted into an S3/EventBridge/Lambda flow while the monolith kept serving.

---

## 10 · Build: split ScreenMaster

> **KEY 10 · BUILD · 3 evenings · from "read about it" to "did it"**

The lab that closes your experience gap. You'll decompose your own cinema system into the §01 architecture, then deliberately break it and watch the patterns save you. Every milestone ends with a sentence you can say in the interview. Don't gold-plate — ugly code, working demos.

### M1 — Skeleton: four services + gateway on Compose

Create `catalog`, `booking`, `payment` (a fake provider: 200 OK after 300ms, with a `FAIL_RATE` env var for later), `notification` — minimal Spring Boot apps — plus `gateway` (Spring Cloud Gateway with the §03 routes). One compose file wires them with RabbitMQ and *two separate Postgres containers* (booking-db, catalog-db) — database-per-service made physical:

```yaml
services:
  gateway:   { build: ./gateway,  ports: ["8080:8080"] }
  catalog:   { build: ./catalog,  environment: [ "DB_URL=jdbc:postgresql://catalog-db/catalog" ] }
  booking:   { build: ./booking,  environment: [ "DB_URL=jdbc:postgresql://booking-db/booking" ] }
  payment:   { build: ./payment,  environment: [ "FAIL_RATE=0" ] }
  notification: { build: ./notification }
  catalog-db:  { image: postgres:16, environment: [ "POSTGRES_PASSWORD=pg" ] }
  booking-db:  { image: postgres:16, environment: [ "POSTGRES_PASSWORD=pg" ] }
  rabbitmq:    { image: rabbitmq:3-management, ports: ["15672:15672"] }
  zipkin:      { image: openzipkin/zipkin, ports: ["9411:9411"] }
```

*Talk track: "I run a five-service system locally with isolated databases — here's the compose file."*

### M2 — Feel the missing JOIN

Booking needs movie titles it no longer owns. Implement "my bookings" twice: (a) API composition — booking calls catalog over HTTP and merges; (b) a tiny CQRS read model — catalog publishes `MovieUpdated` events, booking maintains a local `movie_titles(id, title)` table from them and joins locally. Stop catalog; (a) breaks, (b) keeps working with possibly-stale titles. *Talk track: "I've implemented both answers to cross-service queries and can articulate the staleness tradeoff from experience."*

### M3 — The booking saga (orchestrated)

POST /bookings → booking creates PENDING + holds seats (your databases-volume constraint!) → calls payment (sync) → CONFIRMED on approval, or compensation (release seats, CANCELLED) on decline. Persist saga state on the booking row (`PENDING → CONFIRMED | CANCELLED`) so a crashed orchestrator can resume from the DB. Set `FAIL_RATE=0.3` and fire 50 bookings; verify every failure compensated — zero orphaned seat holds. *Talk track: "my saga survives a 30% payment failure rate with zero leaked seat holds — I verified with a script."*

### M4 — Outbox → RabbitMQ → idempotent consumer

On CONFIRMED, write `BookingConfirmed` to an outbox table in the same transaction (§02 SQL); a `@Scheduled` relay claims rows with `FOR UPDATE SKIP LOCKED` and publishes; notification consumes, "sends" the email (log line), and dedupes via `processed_events`. Prove it: kill the relay mid-batch, restart, confirm exactly one email-log per booking despite redelivery. Two instances of notification (`docker compose up --scale notification=2`) must not double-send. *Talk track: "I've implemented outbox + idempotent consumer and tested duplicate delivery on purpose."*

### M5 — Break payment, watch resilience work

Add the §04 Resilience4j stack to booking's payment client (timeout 2s, 3 retries with backoff, breaker, fallback = stay PENDING + queue for later). Now `docker stop payment` during a load loop and narrate what you observe: first requests eat the timeout, breaker opens (watch `/actuator/circuitbreakers`), subsequent requests fail fast into the fallback, users get the graceful message; `docker start payment`, breaker half-opens, traffic resumes, queued bookings drain. *Talk track: the entire Q11 answer, but past tense and with numbers.*

### M6 — One trace ID across everything

Add Micrometer Tracing + the Zipkin exporter to all services; put `%X{traceId}` in every log pattern; propagate the ID into the RabbitMQ message headers and restore it in the consumer. Make one booking, then: grep all container logs by the traceId (one story, five services), and open the Zipkin waterfall for the same request. Screenshot it for the README. *Talk track: "ask me how I'd debug a cross-service failure — here's the trace from my own system."*

### M7 — Lock the doors with Keycloak

Add a Keycloak container (realm `cinema`, a public client for users, a `booking-service` client with client-credentials). Gateway + every service become resource servers validating the JWT (issuer-uri config); booking calls payment with a relayed token; the M4 relay uses its own client-credentials token. Demo: curl without a token → 401 at the gateway; with a user token → booking succeeds; payment called directly with no token → 401 too (zero trust, not just perimeter). *This milestone is simultaneously the hands-on half of your Keycloak/Spring Security study item — double credit.*

> **✅ Scope guard**
>
> Three evenings means cutting ruthlessly: no Kubernetes (Compose is enough), no real Stripe/PayPal (the fake provider teaches more because you control its failures), no gRPC implementation (the proto file in §03 + the comparison table is interview-sufficient), no UI (curl + RabbitMQ management console). If an evening disappears, do M1, M3, M4, M5 — saga, outbox, breaker are the three demos that change how your answers sound.

---

## 11 · Resources

> **KEY 11 · LINKS · curated for a two-week window**

The guide is self-sufficient; these are for depth and for after. First three are the priority — everything else is optional.

- **microservices.io — Chris Richardson's pattern catalog** — *reference · pairs with §02/§07*
  The industry-standard pattern index this volume's §07 mirrors. Read the Saga, Transactional Outbox, Database per Service, and API Gateway pages — each is a tight 10 minutes with diagrams.

- **Building Microservices, 2nd ed. — Sam Newman** — *book · ch. 1–2, 4–6 in your window*
  The book interviewers have read. Chapters 1–2 (what/modelling = §00–01), 4–6 (communication = §03, workflow/sagas = §02). His "Monolith to Microservices" is the strangler-fig book if migration questions worry you.

- **Resilience4j documentation** — *docs · pairs with §04 and lab M5*
  Short, excellent docs on circuit breaker, retry, bulkhead, time limiter — read the CircuitBreaker page before M5 so the config values mean something.

- **Microservices Patterns — Chris Richardson** — *book · the saga chapters (4–6)*
  If sagas/outbox still feel abstract after §02 + the lab, his worked Java examples are the best treatment in print. Otherwise save for after the interview.

- **Spring Cloud Gateway + Spring Security OAuth2 resource server docs** — *docs · pairs with §05 and M7*
  The two reference pages you'll have open during the lab's gateway and Keycloak milestones; reading ahead is unnecessary.

- **Release It!, 2nd ed. — Michael Nygard** — *book · post-interview*
  Where circuit breakers and bulkheads come from, told through production catastrophes. The best engineering war-story book ever written; reward yourself with it after the offer.

- **Learning Domain-Driven Design — Vlad Khononov** — *book · post-interview depth for §01*
  The modern, readable DDD book (skip the dense Evans "blue book" for now). §01 covers interview-level DDD; this is for when you want the full discipline.

- **DDIA — Kleppmann, ch. 8–9** — *book · stretch*
  "The Trouble with Distributed Systems" and "Consistency and Consensus" — the rigorous foundations under §02/§04. Stretch material; the volume's summaries carry you through interviews.

---

## 12 · The 4-day plan

> **KEY 12 · PLAN · your weakest topic gets the most days**

Four deep days, because this is the gap. Note the deliberate overlaps: M7 advances your Keycloak/Spring Security list, and §02 reuses databases-volume muscle — the volumes compound.

| day | deep block (~3 h) | output that proves it |
|-----|-------------------|-----------------------|
| MS-1 | §00 why · §01 boundaries · §02 data — the conceptual core | explain saga + outbox to a rubber duck without notes |
| MS-2 | §03 comms · §04 resilience · lab M1–M2 | five services up on Compose; both cross-service query styles working |
| MS-3 | §05 security · §06 observability · lab M3–M4 | saga surviving FAIL_RATE=0.3; exactly-once email effect proven |
| MS-4 | §07 patterns · §08 ops · lab M5–M6 (M7 with the Spring volume) · §09 out loud | breaker demo narrated live; one traceId across five logs; 16 answers spoken |

If the schedule slips, protect this order: §00–§02 concepts → M3–M5 demos → Q&A out loud. And in the interview itself, the honest framing wins: "I haven't run microservices in production yet — so I built a five-service system from my own project and broke it on purpose; here's what the circuit breaker did." That sentence beats a memorized definition every single time.

---

*microservices · field guide for Mohamed Khaled · pairs with: OOP · Spring · Databases volumes*