# Microservices: what, why, and what they cost

## What it is
A microservice architecture splits a system into **independently deployable services, each owning one business capability and its own data**. Every word in that sentence is load-bearing.

## Why it exists (what each property buys)
| Property | What it buys | ScreenMaster example |
|---|---|---|
| Independent deployment | small, low-risk releases; team A ships without team B | fix notifications without touching payment |
| Independent scaling | scale only the hot path | 10 booking instances on opening night, 1 of everything else |
| Fault isolation | one crash degrades a feature, not the product | recommendations die; checkout keeps selling |
| Team autonomy | small teams own services end-to-end | architecture mirrors team structure (Conway's law, on purpose) |
| Tech freedom | right tool per job, replaceable parts | a Python ML service beside Java services |

**The honest one-liner:** microservices are *primarily* to let many teams deliver independently at speed; scaling/fault-isolation benefits come second. The first and last rows above are organizational — that's the real driver.

## What they cost — say this unprompted
Every former function call becomes a **network call**: slow, half-failing, or silently lost. Data splits, so cross-service **joins, foreign keys, and ACID transactions stop working** and must be re-earned with [sagas](saga-pattern.md), [read models](database-per-service.md), and [eventual consistency](transactional-outbox.md). You now need infrastructure a monolith never asked for: a [gateway](api-gateway-and-bff.md), [discovery](service-discovery.md), [central logging + tracing](observability.md), and per-service CI/CD. Local dev means running ten containers. This is the **distributed-systems tax**, paid monthly, forever.

## The trap question: "so microservices are better, right?"
**No.** For a small team or unproven product, a *well-modularized monolith* ships faster, debugs easier, and keeps transactions simple. Reach for microservices when **team count, deploy contention, or divergent scaling needs** make the monolith the bottleneck — and extract incrementally ([strangler fig](service-decomposition-ddd.md)), never rewrite. Citing "monolith-first" (Fowler, Newman) signals you've read the literature.

## How this applies here
ScreenMaster is a working Spring Boot **monolith** ([ARCHITECTURE_AND_SCHEMA.md](../ARCHITECTURE_AND_SCHEMA.md)). This project decomposes it as a *learning exercise* — the value is in feeling each cost first-hand: the missing JOIN, the distributed transaction, the partial failure. We're not splitting it because the monolith is broken; we're splitting it to learn the discipline.

## Interview lens
- Lead with the tradeoff, not the benefits. Naming the costs makes you instantly more credible.
- Use your own story: "I work in a Spring Boot monolith; the one piece with different scaling/failure characteristics (the video pipeline) effectively got extracted into an event-driven AWS flow. That's microservices logic in miniature."
