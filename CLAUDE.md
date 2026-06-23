# CLAUDE.md — ScreenMaster Microservices

Guidance for Claude Code (and any human) working in this repo. Read this first.

## What this project is
A **learning project** for Mohamed Khaled to practice microservices by **decomposing the existing ScreenMaster cinema monolith into services**. It is driven by two documents and follows a defined build plan:

- **[docs/microservices-interview-field-guide.md](docs/microservices-interview-field-guide.md)** — the theory and the hands-on lab (milestones M1–M7). This is the *curriculum*.
- **[docs/ARCHITECTURE_AND_SCHEMA.md](docs/ARCHITECTURE_AND_SCHEMA.md)** — the current monolith's data model, APIs, and a proposed decomposition. This is the *system being split*.
- **[docs/BUILD_PLAN.md](docs/BUILD_PLAN.md)** — the step-by-step roadmap (derived from the two above). This is *what we do, in order*.
- **[docs/concepts/](docs/concepts/)** — one focused explainer per annotation / pattern / tool we use. This is the *reference library*.

The goal is **learning by building it properly** — this is a learning project, but it should be treated as **production code that follows industry best practices**. Favor clear, idiomatic, well-structured code; do *not* default to the simplest possible thing. The payoff is *feeling* each microservices cost first-hand (the missing JOIN, the distributed transaction, the partial failure) **and** seeing how a production-grade system addresses it — so the interview answers come from real experience with real practices.

## Who I'm working with
Mohamed is **new to microservices** and learning deliberately. So:
- **Explain as you go.** When you introduce a new annotation, pattern, or tool, point to (or create) its file in `docs/concepts/` and give a one-line "why this, here."
- **Build it as production-grade by default.** Apply best practices (validation, error handling, resilience, observability, tests, idempotency, clean boundaries) rather than the minimum that demos the concept. When a shortcut is genuinely warranted for learning, call it out explicitly and explain the production alternative.
- Tie choices back to the field guide milestone and the schema doc.

## Tech stack (from pom.xml)
- **Spring Boot 4.1.0**, **Java 21**, **Spring Cloud 2025.1.2**.
- Spring Web MVC + WebFlux (`WebClient`/`RestClient`), Spring Data JPA, Spring Security, Thymeleaf.
- **Spring Cloud Gateway** (server-webmvc) and **Netflix Eureka client** already on the classpath.
- **PostgreSQL**, **RabbitMQ**, **Lombok**.
- Build: Maven (`./mvnw`). Local runtime: **Docker Compose** (no Kubernetes — scope guard).

## Target architecture (collapsed for the lab)
Gateway → { **Catalog**, **Booking** (absorbs Theater + Scheduling), **Payment** (fake provider), **Notification** (event consumer), **Identity** (Keycloak) } + **Eureka** discovery. Database-per-service (separate Postgres per owning service). See [docs/concepts/service-decomposition-ddd.md](docs/concepts/service-decomposition-ddd.md).

## Conventions
- **Module layout:** multi-module Maven — a parent `pom.xml` with one child module per service (`gateway`, `discovery`, `catalog`, `booking`, `payment`, `notification`). The current single-module pom becomes the parent.
- **Each service:** its own `@SpringBootApplication`, its own `application.yml`, its own database, its own runnable jar.
- **DI:** constructor injection via Lombok `@RequiredArgsConstructor` + `final` fields. No field `@Autowired`.
- **Logging:** `@Slf4j`, structured, with the trace id in the log pattern. Never `System.out.println` (a flagged monolith gap).
- **Enums:** always `@Enumerated(EnumType.STRING)` (the monolith has fragile ordinal enums — don't repeat that).
- **Schema:** **Flyway migrations**, `ddl-auto=validate` — not `ddl-auto=update`.
- **Cross-service references:** ids + API/event lookups, never cross-service FKs or JOINs. Snapshot immutable facts into the consumer (Booking already snapshots seat price / total).
- **Secrets:** env vars / `.env` for local, never committed. No hard-coded JWT secret.
- **DTOs cross the wire, not entities.**

## Working agreements
- **Never commit, push, or create branches on your own.** Do not run `git commit`, `git push`, `git branch`, or `git checkout -b` unless I explicitly ask you to in that message. When I do ask you to commit, stage and commit exactly what I requested — nothing more.
- **No AI co-author trailer.** Do not add `Co-Authored-By: Claude ...` (or any similar AI attribution) to commit messages.
- When adding a new concept (annotation/pattern/tool) that isn't yet in `docs/concepts/`, **add a short file for it** and link it from `docs/concepts/README.md`.
- Keep `docs/BUILD_PLAN.md` in sync: when a milestone's status changes, update its checkbox/notes there.
- **Build it right the first time.** Apply best practices proactively; don't ship a deliberately rough version expecting to harden it later. (This is a learning-by-doing-it-properly project, not a "ugly code, working demos" project.)

## Build / run (will fill in as services exist)
```bash
./mvnw -q -pl <module> spring-boot:run      # run one service
docker compose up --build                    # run the whole system
docker compose logs -f booking               # follow a service
```

## Where to look
| I need… | Go to |
|---|---|
| What to do next | [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md) |
| Why we split this way | [docs/ARCHITECTURE_AND_SCHEMA.md](docs/ARCHITECTURE_AND_SCHEMA.md) §8 + concepts/service-decomposition-ddd.md |
| What an annotation/pattern means | [docs/concepts/](docs/concepts/) (start at its README) |
| The theory / interview answers | [docs/microservices-interview-field-guide.md](docs/microservices-interview-field-guide.md) |
