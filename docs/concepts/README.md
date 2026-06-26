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
| [jpa-and-hibernate.md](jpa-and-hibernate.md) | `@Entity`, `@Id`, `@GeneratedValue`, relationships, `@Enumerated`, repositories, `@Transactional`, fetch types |
| [liquibase.md](liquibase.md) | Versioned schema migrations, changesets, `DATABASECHANGELOG`, `ddl-auto=validate`, Liquibase vs Flyway |
| [lombok.md](lombok.md) | `@Getter`/`@Setter`, `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` |
| [maven-multi-module.md](maven-multi-module.md) | Parent POM, the reactor, `dependencyManagement` vs `dependencies`, `-pl`/`-am`, one module per service |

### Microservice architecture & patterns
| File | Covers |
|---|---|
| [microservices-overview.md](microservices-overview.md) | What/why/cost, bounded contexts, the distributed-systems tax |
| [service-decomposition-ddd.md](service-decomposition-ddd.md) | Bounded context, aggregate, subdomain, how ScreenMaster splits |
| [database-per-service.md](database-per-service.md) | Private data, no shared tables, FK cuts, API composition vs CQRS read models |
| [saga-pattern.md](saga-pattern.md) | Local transactions + compensation, choreography vs orchestration, the booking saga |
| [transactional-outbox.md](transactional-outbox.md) | Dual-write problem, outbox table, relay, at-least-once delivery |
| [idempotent-consumer.md](idempotent-consumer.md) | Dedupe by event id, exactly-once *effect* |
| [api-gateway-and-bff.md](api-gateway-and-bff.md) | Single front door, routing, edge auth, BFF variant |
| [service-discovery.md](service-discovery.md) | Eureka, client- vs server-side discovery, K8s DNS |

### Communication
| File | Covers |
|---|---|
| [sync-vs-async-comms.md](sync-vs-async-comms.md) | The first fork, REST vs gRPC, command vs event |
| [rabbitmq.md](rabbitmq.md) | Exchange/queue/routing key, producer/consumer, the ScreenMaster email + booking queues |

### Resilience
| File | Covers |
|---|---|
| [resilience-patterns.md](resilience-patterns.md) | Timeouts, retries+backoff+jitter, circuit breaker, bulkhead, Resilience4j, graceful degradation |

### Security
| File | Covers |
|---|---|
| [security-jwt-oauth2.md](security-jwt-oauth2.md) | OAuth2/OIDC, JWT, resource servers, zero trust, client credentials, mTLS, Keycloak |

### Observability
| File | Covers |
|---|---|
| [observability.md](observability.md) | Correlation/trace IDs, structured logs, Micrometer Tracing, Zipkin/OpenTelemetry, RED metrics |

### Ops
| File | Covers |
|---|---|
| [containers-and-compose.md](containers-and-compose.md) | Docker, Compose, images vs containers, the multi-service compose file |
| [kubernetes-vocabulary.md](kubernetes-vocabulary.md) | Pod/Deployment/Service/Ingress/ConfigMap, liveness vs readiness (reference only — not built in this project) |

---

These files are **learning notes**, not API docs. They lean on the two source documents:
- [`../microservices-interview-field-guide.md`](../microservices-interview-field-guide.md) — the theory and the lab.
- [`../ARCHITECTURE_AND_SCHEMA.md`](../ARCHITECTURE_AND_SCHEMA.md) — the monolith we are splitting.