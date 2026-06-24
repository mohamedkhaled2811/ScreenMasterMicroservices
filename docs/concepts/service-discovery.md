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

## Our Eureka server, two settings worth understanding (BUILD_PLAN 1.1)

The `discovery` service is a Eureka **server** (`@EnableEurekaServer`, port 8761). Two bits of its config encode real trade-offs:

**`register-with-eureka: false` + `fetch-registry: false`.** A Eureka server is *also* a Eureka client by default — that's how servers replicate to each other in a cluster (peer awareness). We run a **single standalone node** with no peers, so we tell it not to register with, or pull a registry from, itself. Leaving these `true` makes a lone server log connection-refused retries on boot as it hunts for peers that don't exist.

**`enable-self-preservation: false` — the one that bites in dev.** Self-preservation is a safety mechanism: if the server stops receiving the *expected* number of heartbeats (default: it expects renewals from ~85% of registered instances each minute), it assumes the problem is a **network partition on its own side**, not that the instances are actually dead — so it **stops evicting** anything rather than risk wrongly de-registering healthy services it just can't hear. In production that's usually what you want (a flaky network shouldn't empty your registry). In *this lab* it's the wrong default: we **deliberately `docker stop`** services to watch failure handling (Phases 3 & 5), and with self-preservation on, the registry would keep claiming those stopped instances are `UP` for minutes — hiding exactly the behavior we're trying to observe. So we turn it off, accept the red "self preservation is turned off" dashboard banner, and pair it with a tighter `eviction-interval-timer-in-ms` so dead instances disappear promptly.

> Interview-ready phrasing: *"Self-preservation makes Eureka stop evicting instances when heartbeats drop below a threshold, on the assumption it's a network partition rather than mass death — good in prod, but I disable it locally because I deliberately kill services to demo failure handling and want the registry to tell the truth immediately."*

## Interview lens
"Instances come and go, so hardcoded URLs break. Client-side discovery: services register with Eureka/Consul; callers fetch the list and load-balance. Server-side discovery: callers hit a stable name and infra routes — which is what a Kubernetes Service gives you for free, so on K8s you typically don't run Eureka at all."
