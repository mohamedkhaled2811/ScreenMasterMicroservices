# Kubernetes vocabulary (reference only — not built in this project)

> We run this lab on **Docker Compose, not Kubernetes** (field guide scope guard). This file exists so the vocabulary is accurate when an interviewer leans on it — microservices answers leak ops terms constantly.

## From Compose to Kubernetes — the mental map
Compose runs your system on one machine; **Kubernetes** is the same idea across a *fleet*, declaratively: you describe desired state, controllers make reality match.

| Word | What it is |
|---|---|
| **Pod** | smallest unit — your container (+ maybe a sidecar). |
| **Deployment** | "keep N replicas of this pod spec running"; handles rolling updates. |
| **Service** | stable DNS name + load balancer over those pods — *server-side discovery* (see [service-discovery.md](service-discovery.md)); on K8s you usually don't need Eureka. |
| **Ingress** | routes external HTTP into the cluster (the gateway's neighbor). |
| **ConfigMap** / **Secret** | inject configuration / secrets — externalized config, the 12-factor way. |
| **HPA** (Horizontal Pod Autoscaler) | adds replicas under load — the "scale only the hot service" promise, delivered. |

## Liveness vs readiness — a favorite quick question
Both are probes against your health endpoints (`/actuator/health/...`), with different consequences:
- **Liveness** — "is this process beyond saving?" Fail it → the pod is **restarted** (right for deadlocks).
- **Readiness** — "can it take traffic *right now*?" Fail it → the pod is **removed from the Service's rotation**, not killed (right while warming up, or while a dependency is briefly down).

**The classic mistake to name:** wiring a *dependency* check (is the database reachable?) into **liveness** — then a DB blip makes Kubernetes restart-loop perfectly healthy pods, turning an incident into an outage. **Dependency checks belong in readiness.**

## Config, releases, the 12-factor habit
One image, promoted unchanged through environments; behavior differences come from **externalized config** (env vars, ConfigMaps, Spring Cloud Config). Releases per service are where microservices pay off: small diffs, **rolling updates** by default, **canary** (send 5% of traffic to the new version, watch the RED metrics, then ramp) when risk is higher. CI/CD is per-service: build → test (incl. contract tests) → image → deploy.

## How this relates to our project
Everything we build on Compose has a K8s analog: Compose service → Deployment+Service, `environment:` → ConfigMap/Secret, `--scale` → replicas/HPA, `/actuator/health` → liveness/readiness probes. If we ever migrated, the app code wouldn't change — only the deployment descriptors.

## Interview lens
"I'd start with Compose locally and the platform team's K8s templates in prod. The six words I'd use correctly: Pod, Deployment, Service, Ingress, ConfigMap/Secret, HPA. And the trap I'd avoid: never put a dependency check in liveness — that's a readiness concern, or a DB blip becomes a restart-loop outage."
