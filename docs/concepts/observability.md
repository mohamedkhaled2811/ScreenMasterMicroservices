# Observability: correlation IDs, tracing, structured logs, metrics

## The problem
In a monolith, debugging is reading one log file. In microservices, one user click produces log lines in six containers that restart and vanish. Every observability question is some form of: *"a request failed somewhere across your services — how do you find it?"*

The three pillars: **logs** (why), **traces** (where), **metrics** (that something's wrong, in aggregate).

## Logging that survives distribution
Three upgrades turn monolith logging into microservice logging:
1. **Structured logs** — emit JSON, not prose: `{"level":"ERROR","service":"payment","bookingId":42,...}` — so logs are queryable fields, not grep targets.
2. **Central aggregation** — containers are ephemeral, so logs ship to one searchable store: ELK (Elasticsearch + Logstash/Fluentd + Kibana), Grafana Loki, or CloudWatch. Apps log to **stdout**; collectors do the shipping (12-factor: logs are event streams, not files you manage).
3. **Correlation** — below; everything depends on it.

(This directly fixes the monolith's flagged `System.out.println` debugging — replace with `@Slf4j` structured logging.)

## Correlation IDs — "how do you follow a request?"
At the edge, the gateway stamps each request with a unique **trace id**; every service puts it in its logging context and **propagates it on every outgoing call and every published event**. Now one search — `traceId:7f3a…` — returns the request's whole story across all services, in order.

The W3C header is `traceparent`. In Spring Boot 3 this is **Micrometer Tracing** (successor to Spring Cloud Sleuth — knowing the succession dates your knowledge correctly), which auto-instruments HTTP in/out and drops `traceId`/`spanId` into the SLF4J **MDC**:
```
# logback pattern: %d %-5level [${spring.application.name},%X{traceId},%X{spanId}] %msg%n

10:01:22.310 INFO  [gateway,7f3ab9e2c1d44a02,a1] → POST /api/bookings
10:01:22.317 INFO  [booking,7f3ab9e2c1d44a02,b4] seats held bookingId=42
10:01:22.391 ERROR [payment,7f3ab9e2c1d44a02,c9] charge declined: insufficient_funds
10:01:22.402 INFO  [booking,7f3ab9e2c1d44a02,b4] compensating: seats released
```
**Across async hops:** propagate the trace id into the RabbitMQ message headers and restore it in the consumer, or the email step falls out of the trace.

## Distributed tracing — the same idea, visualized
A **trace** is the whole request; each timed unit of work (an HTTP call, a DB query) is a **span** with a parent — together a tree. Export spans via **OpenTelemetry** (the vendor-neutral standard) to **Zipkin** or **Jaeger** and you get a waterfall: exactly where the 900 ms went, which hop failed, what ran in parallel. **Tracing answers *where*; logs answer *why*.** Production note: tracing is usually **sampled** (e.g. 10%) to control overhead.

## Metrics & alerting
Logs/traces explain individual requests; **metrics** watch the aggregate. Spring Boot **Actuator** + **Micrometer** expose them; **Prometheus** scrapes; **Grafana** dashboards. Track the **RED method** per service: **R**ate (req/s), **E**rrors (failure %), **D**uration (latency percentiles — quote **p95/p99**, never averages; an average hides the one-in-twenty 3-second request).

Two habits worth saying: alert on **symptoms users feel** (error rate, p99) not causes (CPU); and **watch the resilience tooling itself** — a circuit breaker's state is a metric, and a breaker stuck open *is* the incident. Health endpoints (`/actuator/health`) feed orchestrator probes (see [kubernetes-vocabulary.md](kubernetes-vocabulary.md)).

## How we use it here (field guide M6)

**The stack.** Every service carries Micrometer Tracing with the **OpenTelemetry bridge** (`micrometer-tracing-bridge-otel`) and the **Zipkin exporter** (`opentelemetry-exporter-zipkin`), plus Boot 4's tracing auto-configuration. We chose OTel over Brave (decision A1) because OTel is the vendor-neutral CNCF standard — swapping Zipkin for Jaeger/Tempo/Datadog later is an exporter change, not a rewrite, since the *instrumentation* is standard. The log pattern has carried `%X{traceId}/%X{spanId}` since Phase 1; these jars are what made the ids appear with zero log-config edits. Sampling is 100% here (decision E1, `TRACING_SAMPLE_RATE`) so every demo request exists in Zipkin; production would run ~0.1 — recording every trace is expensive, and the decision is made once at the front door and carried in the `traceparent` flag.

**The synchronous path is free.** Gateway → payment → booking and gateway → booking → catalog are plain HTTP, which Micrometer instruments automatically: the gateway starts a trace at the front door, the `traceparent` header carries it, and every log line in every service carries the same id. `@Observed` (decision D2) adds business spans on the four methods that matter — `BookingService.create`, `WebhookProcessor.process`, `PaymentSessionFactory.openNewSession`, `OutboxRelay.drain` — so the waterfall shows *our* work, not just the HTTP hop. (`@Observed` needs the `ObservedAspect` bean to exist or it is silently inert.)

**The outbox gap — the most valuable lesson of the phase.** Our transactional outbox (Phase 3/4) deliberately splits every publish in two: the business transaction writes the outbox row, and a `@Scheduled` relay publishes it seconds later on a scheduler thread. Micrometer can only propagate a trace that is *live on the thread doing the publishing* — and there it is not. So auto-instrumentation silently ends the trace at the outbox row, and the consumer's work (Booking confirming, Notification emailing) falls out of it. The fix is the same principle the outbox itself runs on: **if a fact must survive a commit boundary, write it to the database.** We persist the current `trace_id`/`span_id` on the outbox row at write time (inside the business transaction, where the trace is still live), the relay reads it back and stamps a W3C `traceparent` header on the AMQP message, and each consumer (`PaymentEventListener`, `BookingEventListener`) rebuilds a real child span from it — a true causal chain across the hop. That is the interview story: *"my outbox meant the trace couldn't propagate automatically, so I persisted the trace context alongside the event, the same way I persist the event itself."*

**The contrast with Catalog.** `MovieEventPublisher` publishes `MovieUpserted` directly at AFTER_COMMIT from the request thread (ADR 0001 — deliberately never an outbox), so the trace rides along automatically and the `MovieUpsertedListener` needs no manual restore. We get both shapes in one system: the outbox hop is carried by hand, the direct publish is not — and knowing *which* is which is the lesson.

**Webhooks start a new trace on purpose.** Stripe has never heard of our trace, so a webhook arrives with no `traceparent` and starts a brand-new trace — correct and deliberate: the payment outcome genuinely is a separate causal chain that begins when the user finishes paying, possibly minutes after the booking request ended. We don't fake a parent-child join. We make the two traces *linkable*: the webhook's span is tagged with `paymentId`/`attemptId`/`bookingId`, and the trace id is stored on the `webhook_events` evidence row — so a "customer says I paid and nothing happened" report resolves to exactly what that webhook did.

**Why parent-child, not OTel span links.** OTel's *links* are the more precise modelling for queued work ("caused by, but not synchronously nested"), and the OTel messaging spec recommends them — but Zipkin's UI is parent-child and does not render links, so building against links would mean doing the work and never seeing it. We persist and parent instead, and the honest cost is a visible gap in the waterfall where the scheduler delay sits. Worth knowing both, in an interview: "we parent because Zipkin is parent-child; in a Jaeger/Tempo deployment links would be the precise answer."

**The demo (BUILD_PLAN 6.3).** One booking: `grep` all five container logs by one trace id → one story, in order; open the Zipkin waterfall for that same id → the synchronous session trace, the webhook trace tagged with `paymentId`, and the confirm→email chain hanging off the webhook trace.

## Interview lens
"Metrics tell me something is wrong (RED per service, Prometheus/Grafana); traces tell me where (OpenTelemetry → Zipkin/Jaeger waterfall); logs tell me why (structured JSON, centrally aggregated). The glue is the correlation/trace id stamped at the gateway and propagated on every call and event."
