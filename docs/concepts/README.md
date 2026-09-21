# Concepts — the "explain everything we use" library

> Every annotation, pattern, and tool this project touches gets one focused file here.
> Read a file the moment you first meet the thing in code; come back when you forget it.

Each concept file follows the same shape so they're predictable:

1. **What it is** — one or two plain sentences.
2. **Why it exists** — the problem it solves.
3. **Example** — minimal code, usually tied to ScreenMaster.
4. **How we use it here** — where it shows up in *this* project.
5. **Gotchas / interview lens** — the thing that trips people up or that an interviewer probes.

## Index

### Spring & Java foundations
| File | Covers |
|---|---|
| [spring-core-and-beans.md](spring-core-and-beans.md) | IoC container, beans, dependency injection, `@Component`/`@Service`/`@Configuration`, `@Bean`, `@Autowired` |
| [spring-boot-annotations.md](spring-boot-annotations.md) | `@SpringBootApplication`, auto-configuration, starters, `@ConfigurationProperties`, profiles |
| [spring-web-annotations.md](spring-web-annotations.md) | `@RestController`, `@RequestMapping`, `@GetMapping`, `@RequestBody`, `@PathVariable`, `ResponseEntity`, `RestClient`/`WebClient` |
| [error-handling-problemdetail.md](error-handling-problemdetail.md) | `@RestControllerAdvice`, `ResponseEntityExceptionHandler`, RFC 9457 `ProblemDetail`, error-code enums, domain exceptions, the error contract between services |
| [openapi-springdoc.md](openapi-springdoc.md) | springdoc-openapi, OpenAPI 3.1 spec + Swagger UI, `@Operation`/`@ApiResponse`/`@Tag`, `@ParameterObject`, the gateway `Server` URL for the `/api` prefix, aggregated UI at the gateway, documenting the custom error `code` |
| [jpa-and-hibernate.md](jpa-and-hibernate.md) | `@Entity`, `@Id`, `@GeneratedValue`, relationships, `@Enumerated`, repositories, **Specifications / dynamic queries**, `@Transactional`, fetch types |
| [pagination-and-filtering.md](pagination-and-filtering.md) | The "listings paginate; they never dump" convention — `Pageable`, `JpaSpecificationExecutor`, filter DTOs, sort whitelists, `max-page-size`, the stable `PagedModel` (`VIA_DTO`) envelope, the two-step lazy fetch |
| [liquibase.md](liquibase.md) | Versioned schema migrations, changesets, `DATABASECHANGELOG`, `ddl-auto=validate`, Liquibase vs Flyway |
| [lombok.md](lombok.md) | `@Getter`/`@Setter`, `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` |
| [maven-multi-module.md](maven-multi-module.md) | Parent POM, the reactor, `dependencyManagement` vs `dependencies`, `-pl`/`-am`, one module per service |

### Microservice architecture & patterns
| File | Covers |
|---|---|
| [microservices-overview.md](microservices-overview.md) | What/why/cost, bounded contexts, the distributed-systems tax |
| [service-decomposition-ddd.md](service-decomposition-ddd.md) | Bounded context, aggregate, subdomain, how ScreenMaster splits |
| [database-per-service.md](database-per-service.md) | Private data, no shared tables, FK cuts, API composition vs CQRS read models |
| [cqrs-read-model.md](cqrs-read-model.md) | Local event-fed replica of another service's data, eventual consistency, lazy backfill, the ordering guard — Booking's movie-title cache |
| [saga-pattern.md](saga-pattern.md) | Local transactions + compensation, choreography vs orchestration, the booking saga |
| [transactional-outbox.md](transactional-outbox.md) | Dual-write problem, outbox table, relay, at-least-once delivery |
| [pessimistic-locking.md](pessimistic-locking.md) | `SELECT ... FOR UPDATE`, check-then-insert without races, never hold a lock across I/O |
| [idempotent-consumer.md](idempotent-consumer.md) | Dedupe by event id, exactly-once *effect* |
| [notification-channels.md](notification-channels.md) | The deliver-vs-decide seam, why one interface + one impl earns its place here, throw-never-swallow under a claim, retry + dead-letter queue, the machine-token recipient lookup and the coupling it buys |
| [payment-gateway-integration.md](payment-gateway-integration.md) | Hosted checkout sessions vs charges, Payment vs PaymentAttempt, the gateway port + registry, webhook verification/storage/idempotency, money in minor units, refunds, reconciliation |
| [api-gateway-and-bff.md](api-gateway-and-bff.md) | Single front door, routing, edge auth, BFF variant |
| [service-discovery.md](service-discovery.md) | Eureka, client- vs server-side discovery, K8s DNS |

### Communication
| File | Covers |
|---|---|
| [sync-vs-async-comms.md](sync-vs-async-comms.md) | The first fork, REST vs gRPC, command vs event |
| [rabbitmq.md](rabbitmq.md) | Exchange/queue/routing key, producer/consumer, the ScreenMaster email + booking queues |
| [scheduled-resumable-sync.md](scheduled-resumable-sync.md) | `@Scheduled` trigger + cursor table, per-page transactions, bounded runs, idempotent upsert — the Catalog↔TMDB sync |
| [html-email-rendering.md](html-email-rendering.md) | Why email markup is tables + inline styles, no CSS `background-image`, readable with images blocked, multipart HTML+text, and the Thymeleaf comment leak that shipped developer notes to customers |

### Resilience
| File | Covers |
|---|---|
| [resilience-patterns.md](resilience-patterns.md) | Timeouts, retries+backoff+jitter, circuit breaker, bulkhead, Resilience4j, graceful degradation |

### Security
| File | Covers |
|---|---|
| [security-jwt-oauth2.md](security-jwt-oauth2.md) | OAuth2/OIDC, JWT, resource servers, zero trust, client credentials, mTLS, Keycloak |
| [keycloak-clients-and-scopes.md](keycloak-clients-and-scopes.md) | What a Keycloak *client* is vs a user, our four clients (`cinema-web` PKCE, `cinema-dev-cli`, the two machine clients), client scopes → claims, and which claims our code actually reads |
| [current-user-resolution.md](current-user-resolution.md) | `@CurrentUser` + a `HandlerMethodArgumentResolver` as the identity seam — `X-User-Id` header now, JWT `sub` in Phase 7; `user_id` as an opaque cross-service ref, designing for a known-future change without over-building |

### Observability
| File | Covers |
|---|---|
| [observability.md](observability.md) | Correlation/trace IDs, structured logs, Micrometer Tracing, Zipkin/OpenTelemetry, RED metrics |

### Ops
| File | Covers |
|---|---|
| [ci-and-coverage.md](ci-and-coverage.md) | GitHub Actions CI, JaCoCo bytecode instrumentation, line vs branch coverage, why coverage is a smoke detector not a grade, no-gate-first policy |
| [docker-fundamentals.md](docker-fundamentals.md) | Images/containers/registry, the layer + cache model, Dockerfile instructions, multi-stage builds, volumes/networks/ports, command reference |
| [containers-and-compose.md](containers-and-compose.md) | Docker, Compose, images vs containers, the multi-service compose file |
| [kubernetes-vocabulary.md](kubernetes-vocabulary.md) | Pod/Deployment/Service/Ingress/ConfigMap, liveness vs readiness (reference only — not built in this project) |

---

These files are **learning notes**, not API docs. They lean on the two source documents:
- [`../microservices-interview-field-guide.md`](../microservices-interview-field-guide.md) — the theory and the lab.
- [`../ARCHITECTURE_AND_SCHEMA.md`](../ARCHITECTURE_AND_SCHEMA.md) — the monolith we are splitting.