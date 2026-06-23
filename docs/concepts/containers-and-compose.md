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
Database-per-service made *physical* — **two separate Postgres containers**:
```yaml
services:
  gateway:      { build: ./gateway,  ports: ["8080:8080"] }
  catalog:      { build: ./catalog,  environment: [ "DB_URL=jdbc:postgresql://catalog-db/catalog" ] }
  booking:      { build: ./booking,  environment: [ "DB_URL=jdbc:postgresql://booking-db/booking" ] }
  payment:      { build: ./payment,  environment: [ "FAIL_RATE=0" ] }   # fake provider
  notification: { build: ./notification }
  catalog-db:   { image: postgres:16, environment: [ "POSTGRES_PASSWORD=pg" ] }
  booking-db:   { image: postgres:16, environment: [ "POSTGRES_PASSWORD=pg" ] }
  rabbitmq:     { image: rabbitmq:3-management, ports: ["15672:15672"] }
  zipkin:       { image: openzipkin/zipkin,     ports: ["9411:9411"] }
```
Key ideas:
- **Service name = DNS name.** `booking` reaches Postgres at `jdbc:postgresql://booking-db/...` — Compose's internal network resolves `booking-db` for you. (Same idea as Kubernetes Service DNS; see [service-discovery.md](service-discovery.md).)
- **`ports: ["8080:8080"]`** publishes a container port to your host; only the gateway and the management UIs need that — internal services talk over the Compose network.
- **`environment`** injects config (DB URL, `FAIL_RATE`, secrets) per service — externalized config, no rebuild.
- **`build: ./booking`** builds the image from that folder's Dockerfile; `image:` pulls a prebuilt one.

## Useful commands
```bash
docker compose up --build          # build images and start everything
docker compose up --scale notification=2   # two notification instances (M4 dedupe test)
docker compose logs -f booking     # follow one service's logs
docker stop payment                # kill a service to watch resilience (M5)
docker compose down -v             # stop and delete volumes
```

## How we use it here
Compose is the *entire* runtime for this lab — **no Kubernetes** (field guide scope guard). One file wires every service, two Postgres instances, RabbitMQ, Zipkin, and later Keycloak. The existing `compose.yaml` (one Postgres) gets replaced by this multi-service file at M1.

## Interview lens
"Compose runs the whole system locally from one file — each service, its own database, the broker, tracing. It's the same declarative idea as Kubernetes but single-host; I'd start with Compose locally and use the platform team's K8s templates in prod."
