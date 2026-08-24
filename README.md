# ScreenMaster — Microservices

[![CI](https://github.com/mohamedkhaled2811/ScreenMasterMicroservices/actions/workflows/ci.yml/badge.svg)](https://github.com/mohamedkhaled2811/ScreenMasterMicroservices/actions/workflows/ci.yml)

A learning-by-building project that **decomposes the ScreenMaster cinema monolith into microservices**, treated as production-grade code (best practices, not the minimum that demos a concept). It is for two audiences at once: someone *learning* microservices step by step, and someone *fluent* who wants to see how each pattern is actually wired.

> **Status:** early. Services are being built incrementally. This README starts as a stub and **grows with the implementation** — architecture diagrams and per-pattern sections (saga, outbox, database-per-service, gateway/discovery) are added as each piece lands, not drawn up front.

## What this is

The original ScreenMaster is a Spring Boot monolith for a cinema: movie catalog (synced from TMDB), theaters/screens/seats, showtimes, seat booking, payments, and email notifications. This repo splits it along bounded contexts into independent services, each owning its own database, communicating over REST + RabbitMQ, behind an API gateway with Eureka service discovery.

**Target services:** Gateway → { Catalog · Booking (absorbs Theater + Scheduling) · Payment · Notification · Identity } + Eureka discovery.

The point isn't a finished product — it's *feeling* each microservices cost first-hand (the missing JOIN, the distributed transaction, the partial failure) and then handling it the way a real system would.

## Tech stack

Spring Boot 4.1.0 · Java 21 · Spring Cloud 2025.1.2 · Spring Web MVC + WebFlux · Spring Data JPA · Spring Security · Spring Cloud Gateway · Netflix Eureka · PostgreSQL · RabbitMQ · Flyway · Lombok. Build with Maven (`./mvnw`); run locally with Docker Compose.

## How to run

> Most services don't exist yet — these are the commands they'll use as they come online. The current `compose.yaml` only stands up Postgres.

```bash
./mvnw -q -pl <module> spring-boot:run   # run one service (e.g. catalog, booking)
docker compose up --build                # run the whole system
docker compose logs -f <service>         # follow one service's logs
```

## Where to look

| I need… | Go to |
|---|---|
| The architecture & patterns deep-dive | this README (added as we build) + [`docs/`](docs/) |
| What to do next | [`docs/BUILD_PLAN.md`](docs/BUILD_PLAN.md) |
| Why the system splits this way | [`docs/ARCHITECTURE_AND_SCHEMA.md`](docs/ARCHITECTURE_AND_SCHEMA.md) |
| What an annotation / pattern / tool means | [`docs/concepts/`](docs/concepts/) |
| The theory & interview answers | [`docs/microservices-interview-field-guide.md`](docs/microservices-interview-field-guide.md) |

---

<!--
  README growth plan — fill these in as each piece is implemented (do not draw diagrams ahead of the code):
    • Architecture overview      → hero diagram (hand-authored SVG) once the service set is real
    • API Gateway + discovery    → mermaid: request → resolve → route → forward
    • Database-per-service       → diagram of the boundary/FK cuts + the snapshot pattern
    • Saga (booking → payment)   → mermaid sequence: BookingCreated → Payment → confirm/compensate + 15-min expiry
    • Transactional outbox       → diagram: same-TX write to outbox table → relay → RabbitMQ
  Decide format per diagram: mermaid for flows/sequences, hand-authored SVG for the headline architecture diagram.
-->