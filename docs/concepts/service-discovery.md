# Service discovery

## What it is
A way for services to find each other's network addresses **without hardcoding URLs**, given that instances come and go (scaling, restarts, crashes). This project uses **Netflix Eureka** (the `spring-cloud-starter-netflix-eureka-client` dependency is already in `pom.xml`).

## Why it exists
Hardcoded URLs die first: an instance restarts on a new port, you scale from 1 to 5, a pod gets rescheduled — the address you baked in is now wrong. Discovery turns "call `http://10.0.3.7:8082`" into "call whatever instances of `booking` are currently healthy."

## Two models

### Client-side discovery (Eureka, Consul)
Services **register** themselves with a **registry** on startup ("I'm `booking`, at host:port, and I'm healthy"). Callers **fetch the instance list** from the registry and load-balance themselves (Spring Cloud LoadBalancer). The `lb://booking` URI in the gateway uses exactly this.

```
[booking-1]──register──▶ ┌──────────┐
[booking-2]──register──▶ │  EUREKA  │ ◀──"who is booking?"── [gateway / caller]
[booking-3]──register──▶ └──────────┘ ──[booking-1,2,3]──▶  (caller picks one)
```

### Server-side discovery (Kubernetes)
Callers hit a **stable name** and the infrastructure routes. Kubernetes gives this for free: a `Service` is a stable DNS name (`http://payment`) that load-balances over healthy pods. **On Kubernetes you usually don't need Eureka** — saying so shows your knowledge isn't frozen in 2017.

## How we use it here
- A small **Eureka server** service (the registry).
- Catalog, booking, payment, notification each run a **Eureka client** and register on startup.
- The **gateway** resolves `lb://catalog` etc. through Eureka and load-balances.

This is the "client-side discovery" model, appropriate for a Docker Compose lab. (If we later moved to Kubernetes, we'd drop Eureka and use K8s `Service` DNS — see [kubernetes-vocabulary.md](kubernetes-vocabulary.md).)

## Discovery vs gateway vs load balancing — keep them distinct
- **Discovery** answers *where are the instances of X?*
- **Load balancing** answers *which one of those instances do I call this time?* (Spring Cloud LoadBalancer, client-side.)
- **Gateway** is the *external* front door; it *uses* discovery to route inward.

## Interview lens
"Instances come and go, so hardcoded URLs break. Client-side discovery: services register with Eureka/Consul; callers fetch the list and load-balance. Server-side discovery: callers hit a stable name and infra routes — which is what a Kubernetes Service gives you for free, so on K8s you typically don't run Eureka at all."
