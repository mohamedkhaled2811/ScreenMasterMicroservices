# Containers & Docker Compose

## What it is
A **container** is a process plus its dependencies, packaged from an **image** and run in isolation. **Docker Compose** runs a *set* of containers together on one machine from a single YAML file — which is exactly how we run the whole microservice system locally.

## Why it exists
Microservices means N runnable services + their backing stores (Postgres, RabbitMQ, Keycloak, Zipkin). Starting them by hand, in order, with the right ports and env vars, is error-prone. Compose declares the whole topology once: `docker compose up` brings the system up, `down` tears it cleanly away.

## Image vs container vs registry
- **Image** — the immutable build artifact (your jar + a JRE base layer). Built from a `Dockerfile`.
- **Container** — a running instance of an image. Many containers from one image (that's horizontal scaling: `--scale notification=2`).
- **Registry** — where images are stored/shared (Docker Hub, ECR).

A typical service `Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jre
COPY target/booking-*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

## The lab's compose file (field guide M1)
Database-per-service made *physical* — **three separate Postgres containers** (one per owning
service: catalog, booking, **and payment** — payment owns a `payments` table, so it gets its own DB):
```yaml
services:
  discovery:    { build: { context: ., dockerfile: discovery/Dockerfile }, ports: ["8761:8761"] }
  gateway:      { build: { context: ., dockerfile: gateway/Dockerfile },   ports: ["8080:8080"] }
  catalog:      { build: { context: ., dockerfile: catalog/Dockerfile },
                  environment: [ "CATALOG_DB_URL=jdbc:postgresql://catalog-db:5432/catalog",
                                 "EUREKA_INSTANCE_PREFER_IP_ADDRESS=true" ] }
  booking:      { build: { context: ., dockerfile: booking/Dockerfile },   ... }
  payment:      { build: { context: ., dockerfile: payment/Dockerfile },   environment: [ "FAIL_RATE=0.0" ] }
  notification: { build: { context: ., dockerfile: notification/Dockerfile } }
  catalog-db:   { image: postgres:16, environment: [ "POSTGRES_DB=catalog", "POSTGRES_PASSWORD=postgres" ] }
  booking-db:   { image: postgres:16, environment: [ "POSTGRES_DB=booking", "POSTGRES_PASSWORD=postgres" ] }
  payment-db:   { image: postgres:16, environment: [ "POSTGRES_DB=payment", "POSTGRES_PASSWORD=postgres" ] }
  rabbitmq:     { image: rabbitmq:3-management, ports: ["15672:15672"] }   # wired in Phase 4
  zipkin:       { image: openzipkin/zipkin,     ports: ["9411:9411"] }     # wired in Phase 6
```
*(The real [`compose.yaml`](../../compose.yaml) adds `pg_isready` healthchecks, `depends_on`, named
volumes, and a dedicated network — shown trimmed here for the idea.)*

Key ideas:
- **Service name = DNS name.** `booking` reaches Postgres at `jdbc:postgresql://booking-db:5432/...` — Compose's internal network resolves `booking-db` for you. (Same idea as Kubernetes Service DNS; see [service-discovery.md](service-discovery.md).)
- **`ports: ["8080:8080"]`** publishes a container port to your host; only the gateway, the Eureka dashboard, and the management UIs need that — internal services talk over the Compose network.
- **`environment`** injects config (DB URL, `FAIL_RATE`, secrets) per service — externalized config, no rebuild. Every service already reads `${VAR:default}` env hooks, so Compose adds **zero `application.yml` edits**.
- **`build: { context: ., dockerfile: <svc>/Dockerfile }`** — note the context is the **repo root**, not the module folder. Our multi-module reactor's build needs the parent pom + `mvnw` wrapper, which live at the root, so we can't use the `build: ./booking` shorthand. Each Dockerfile is **multi-stage** (Maven build → JRE runtime). See [docker-fundamentals.md](docker-fundamentals.md) §5–§6.

### Eureka + Compose: register a *reachable* address
A client that registers under its container hostname (a random hash) is **unreachable** to siblings,
so `lb://catalog` would resolve to a dead address. We set `EUREKA_INSTANCE_PREFER_IP_ADDRESS=true`
on each app service so it registers its **routable Compose-network IP** instead. (Alternative:
`EUREKA_INSTANCE_HOSTNAME=<service-name>`.) This is the small but essential glue between discovery
and the container network — see [service-discovery.md](service-discovery.md).

## Useful commands
```bash
docker compose up --build          # build images and start everything
docker compose up --scale notification=2   # two notification instances (M4 dedupe test)
docker compose logs -f booking     # follow one service's logs
docker stop payment                # kill a service to watch resilience (M5)
docker compose down -v             # stop and delete volumes
```

## How we use it here
Compose is the *entire* runtime for this lab — **no Kubernetes** (field guide scope guard). One file wires every service, three Postgres instances (catalog/booking/payment), RabbitMQ, Zipkin, and later Keycloak. The monolith's old `compose.yaml` (one Postgres) was replaced by this multi-service file at M1 (1.4).

## Interview lens
"Compose runs the whole system locally from one file — each service, its own database, the broker, tracing. It's the same declarative idea as Kubernetes but single-host; I'd start with Compose locally and use the platform team's K8s templates in prod."
