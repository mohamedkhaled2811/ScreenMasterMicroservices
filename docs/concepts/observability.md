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
Add Micrometer Tracing + a Zipkin exporter to every service; put `%X{traceId}` in every log pattern; propagate the id into RabbitMQ headers and restore it in the consumer. Make one booking, then: grep all container logs by the traceId (one story, five services) and open the Zipkin waterfall for the same request. Talk track: "ask me how I'd debug a cross-service failure — here's the trace from my own system."

## Interview lens
"Metrics tell me something is wrong (RED per service, Prometheus/Grafana); traces tell me where (OpenTelemetry → Zipkin/Jaeger waterfall); logs tell me why (structured JSON, centrally aggregated). The glue is the correlation/trace id stamped at the gateway and propagated on every call and event."
