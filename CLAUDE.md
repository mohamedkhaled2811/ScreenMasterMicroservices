# CLAUDE.md — ScreenMaster Microservices

Guidance for Claude Code (and any human) working in this repo. Read this first.

## What this project is
A **learning project** for Mohamed Khaled to practice microservices by **decomposing the existing ScreenMaster cinema monolith into services**. It is driven by one document plus the reference library:

- **[docs/microservices-interview-field-guide.md](docs/microservices-interview-field-guide.md)** — the theory and the hands-on lab (milestones M1–M7). This is the *curriculum*.
- **[docs/concepts/](docs/concepts/)** — one focused explainer per annotation / pattern / tool we use. This is the *reference library*.

The goal is **learning by building it properly** — this is a learning project, but it should be treated as **production code that follows industry best practices**. Favor clear, idiomatic, well-structured code; do *not* default to the simplest possible thing. The payoff is *feeling* each microservices cost first-hand (the missing JOIN, the distributed transaction, the partial failure) **and** seeing how a production-grade system addresses it — so the interview answers come from real experience with real practices.

## Who I'm working with
Mohamed is **new to microservices** and learning deliberately. So:
- **Explain as you go.** When you introduce a new annotation, pattern, or tool, point to (or create) its file in `docs/concepts/` and give a one-line "why this, here."
- **Build it as production-grade by default.** Apply best practices (validation, error handling, resilience, observability, tests, idempotency, clean boundaries) rather than the minimum that demos the concept. When a shortcut is genuinely warranted for learning, call it out explicitly and explain the production alternative.
- Tie choices back to the field-guide milestone and the relevant concept note / service code.

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
- **Package-by-layer inside a service:** don't leave classes flat in the service root package — group them into folders by responsibility under `com.gr74.<service>`: `model/` (JPA entities + their enums), `repository/` (Spring Data repositories), `service/` (business logic), `controller/` (REST controllers), `dto/` (wire types), `exception/` (error codes + domain exceptions + the advice), `config/` (`@ConfigurationProperties` and other config). Domain-specific clusters (e.g. payment's `provider/`) get their own folder. Only the `@SpringBootApplication` class stays in the root package. `payment/` is the reference implementation.
- **DI:** constructor injection via Lombok `@RequiredArgsConstructor` + `final` fields. No field `@Autowired`.
- **Logging:** `@Slf4j`, structured, with the trace id in the log pattern. Never `System.out.println` (a flagged monolith gap).
- **Enums:** always `@Enumerated(EnumType.STRING)` (the monolith has fragile ordinal enums — don't repeat that).
- **Schema:** **Liquibase migrations**, `ddl-auto=validate` — not `ddl-auto=update`.
- **Cross-service references:** ids + API/event lookups, never cross-service FKs or JOINs. Snapshot immutable facts into the consumer (Booking already snapshots seat price / total).
- **Secrets:** env vars / `.env` for local, never committed. No hard-coded JWT secret.
- **DTOs cross the wire, not entities.**
- **Listings paginate; they never dump.** Every endpoint that returns a collection MUST be paginated (a `Page`-shaped body), never an unbounded "all rows" array — no exemptions, not even small reference lists. Filter dynamically with a nullable filter DTO → composed JPA `Specification` (an absent field is a no-op); page/size/sort ride on `Pageable`. Enforce a bounded default size and a hard `max-page-size` server-side, whitelist the sortable fields per resource (an unknown sort field is a coded `*_VALIDATION_ERROR`, never a leaked 500), and serialize the page as the stable `PagedModel` envelope (`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`) — both configured in each service's `config/WebPagingConfig`. Pages are 0-indexed. See [docs/concepts/pagination-and-filtering.md](docs/concepts/pagination-and-filtering.md); `catalog`'s `GET /movies` and `booking`'s inventory listings are the reference.
- **Errors carry a machine-readable code.** Each service has an `exception/` package with a `<Service>ErrorCode` enum (the stable contract sibling services branch on), domain exceptions extending a base `<Service>Exception` that carries an error code, and one `@RestControllerAdvice` (`GlobalExceptionHandler` extending `ResponseEntityExceptionHandler`) that renders every error — ours and the framework's (validation, missing headers) — as an RFC 9457 `ProblemDetail` (`application/problem+json`) with a custom `code` property. Throw a coded exception; never let a raw exception escape as an opaque 500, and never `return` an error from a controller. See [docs/concepts/error-handling-problemdetail.md](docs/concepts/error-handling-problemdetail.md); `payment/` is the reference implementation.
- **Diagrams follow one authoring spec.** To author *any* diagram, read [docs/diagrams/README.md](docs/diagrams/README.md) first and follow it — **do not** load `docs/diagrams/template-reference.excalidraw` (that 40 KB JSON is only the rendered exemplar; the spec carries every value). Diagrams are teaching aids for a repo reader learning microservices: **high-level** (a service or the whole system + the patterns it uses) or **low-level** (one pattern, applied in the real code). Each diagram ships as a committed trio — `.excalidraw` + `.png` + `.md` sidecar — service-scoped ones under `<service>/docs/diagrams/`, whole-system ones under root `docs/diagrams/`. JSON mechanics come from the `excalidraw-diagram` skill; the ScreenMaster style/content rules come from the spec.

## Working agreements
- **Never commit, push, or create branches on your own.** Do not run `git commit`, `git push`, `git branch`, or `git checkout -b` unless I explicitly ask you to in that message. When I do ask you to commit, stage and commit exactly what I requested — nothing more.
- **No AI co-author trailer.** Do not add `Co-Authored-By: Claude ...` (or any similar AI attribution) to commit messages.
- When adding a new concept (annotation/pattern/tool) that isn't yet in `docs/concepts/`, **add a short file for it** and link it from `docs/concepts/README.md`.
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
| Why we split this way | [docs/concepts/service-decomposition-ddd.md](docs/concepts/service-decomposition-ddd.md) + the service `model/` entities and Liquibase changelogs as source of truth |
| What an annotation/pattern means | [docs/concepts/](docs/concepts/) (start at its README) |
| The theory / interview answers | [docs/microservices-interview-field-guide.md](docs/microservices-interview-field-guide.md) |
| How to author a diagram | [docs/diagrams/README.md](docs/diagrams/README.md) (the style + content spec — read before drawing) |
