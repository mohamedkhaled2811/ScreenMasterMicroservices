# Resilience: timeouts, retries, circuit breakers, bulkheads

## The defining fact
In distributed systems, **something is always partially broken**. A service that assumes its dependencies are up is the one that takes the platform down. This is a toolbox, ordered from "always" to "when needed."

## 1. Timeouts — the non-negotiable baseline
Every network call gets an explicit timeout — both **connect** (can I reach you? short, ~1 s) and **read** (how long may the answer take? sized from the dependency's real p99, not a guess).

Why first: without timeouts, a slow dependency makes *your* threads pile up waiting → your thread/connection pool exhausts → **you're** down too. That's a **cascading failure**, and "slow" is more dangerous than "down" because detection takes the full wait. In long chains, propagate a **deadline** ("this request has 2 s left") rather than each hop independently waiting 5 s (gRPC does this natively).

## 2. Retries — powerful and dangerous
Transient failures (a blip, a restarting pod) deserve a retry; real failures don't. The checklist that turns "I'd retry" into a senior answer:
1. **Only retry idempotent operations** — a timed-out charge may have succeeded; retrying double-charges unless the operation carries an **idempotency key** (Stripe's `Idempotency-Key` header is the canonical example).
2. **Exponential backoff with jitter** — 100 ms, 200 ms, 400 ms ± randomness, because synchronized retries from a thousand clients hammer the recovering service in waves (a **retry storm** / thundering herd).
3. **Cap attempts** (2–3) and budget retries.
4. **Never retry business errors** — 400/422 means the request is wrong; only 5xx/timeouts/connection failures are retryable.

Amplification math: if A retries 3× and B (called by A) retries 3×, one click becomes 9 calls to C. **Retry at one layer, not every layer.**

## 3. Circuit breaker — fail fast, recover gracefully
When a dependency is properly down, even fast-failing retries waste resources. A breaker watches the failure rate and, past a threshold, **stops calling entirely** for a cooldown.

```
        failure rate > 50% over window
 CLOSED ───────────────────────────▶ OPEN  (calls fail fast, no network I/O)
 (normal)◀── trial calls succeed ──┐    │ cooldown elapses
                                    └ HALF-OPEN ◀┘  (a few trial calls;
                                       fail → back to OPEN)
```
Why failing fast is a *feature*: users get an instant fallback instead of a 30 s hang; your threads are freed (no pile-up → no cascade); and the sick dependency gets breathing room to recover instead of a pile-on.

In Spring: **Resilience4j** (Hystrix's successor — mention the succession).
```java
@CircuitBreaker(name = "payment", fallbackMethod = "paymentUnavailable")
@Retry(name = "payment")          // Retry wraps the breaker: each attempt is counted
@TimeLimiter(name = "payment")
public CompletableFuture<ChargeResult> charge(ChargeRequest req) { ... }

private CompletableFuture<ChargeResult> paymentUnavailable(ChargeRequest req, Throwable t) {
    // degrade, don't die: keep the booking PENDING, queue a retry,
    // tell the user "payment is taking longer than usual — we'll email your tickets."
    return CompletableFuture.completedFuture(ChargeResult.pendingAsync(req.bookingId()));
}
```
```yaml
resilience4j:
  circuitbreaker.instances.payment:
    slidingWindowSize: 20
    failureRateThreshold: 50
    waitDurationInOpenState: 10s
    permittedNumberOfCallsInHalfOpenState: 3
  retry.instances.payment: { maxAttempts: 3, waitDuration: 200ms, enableExponentialBackoff: true }
  timelimiter.instances.payment: { timeoutDuration: 2s }
```

## 4. Bulkheads, rate limits, degradation
- **Bulkhead** (ship compartments): each dependency gets its own bounded thread/connection pool, so a flood toward Payment can't drown the threads Catalog needs.
- **Rate limiting** at the gateway protects you from clients; **load shedding** (reject excess early with 429/503) protects you from yourself — rejecting 10% fast beats serving 100% slowly until collapse.
- **Graceful degradation** by design: recommendations down → show generic popular movies; seat-map slow → serve the 5-second-old cached map with a "refreshing…" hint. Product keeps working, dimmer.

## How we use it here (field guide M5)

**Payment's gateway clients** — Stripe, Paymob and the controllable Sandbox — are wrapped in
Resilience4j, one policy set **per gateway**. A decorator, `ResilientPaymentGateway`, implements the
`PaymentGateway` port and names each Resilience4j instance from `delegate.type()` (`stripe`,
`paymob`, `sandbox`), so every gateway gets its own breaker, bulkhead and retry. `ResilienceConfig`
wraps each adapter bean automatically (a new gateway is still just a `@Component`);
`GatewayRegistry` consumes the decorated list; and no caller — `PaymentSessionFactory`,
`RefundService`, `ReconciliationJob`, `WebhookController` — changes.

The method-by-method policy split is the load-bearing decision:

- `createSession` and `fetchStatus` run breaker + bulkhead + **retry** (3 attempts, 200ms exponential
  backoff ×2 + jitter). The retry is only safe because `createSession` forwards an idempotency key
  (Stripe `Idempotency-Key`, Paymob `merchant_order_id`) and `fetchStatus` is a pure read — the
  interview line is *"the retry is only safe because the idempotency key makes it non-duplicating."*
- `refund` runs breaker + bulkhead but **no retry**: a timed-out refund may have been processed, and
  a blind retry risks a double refund — real money the local `Σ refunds ≤ amount` invariant cannot
  see. The operator retries a failed refund manually.
- `parseAndVerifyWebhook` is **completely undecorated**: it is local CPU work, and a webhook refused
  by our own breaker would make a real gateway retry its delivery for hours — and webhooks are the
  only path to `PAID`. `type()` and `supportedCurrencies()` also delegate untouched (no I/O; `type()`
  is the registry map key).

**Only `GatewayException` counts as a breaker failure — and only it is retried.** The breaker's
`record-exceptions` and the retry's `retry-exceptions` (a different key!) both list only
`GatewayException`. A declined card is a *successful* call that returns a `FAILED` status through
the normal path; confusing the two would trip the breaker on ordinary business outcomes and refuse
healthy traffic. The narrow lists also keep a breaker-open fast-fail
(`CallNotPermittedException`) and a bulkhead rejection from being retried.

**Breaker-open is a fast failure into the existing coded 503, not a fallback gateway.** There is no
useful degraded answer here — a checkout URL is either real or it isn't — so `CallNotPermittedException`
maps to `PAYMENT_GATEWAY_UNAVAILABLE` (503), and a bulkhead rejection gets its own
`PAYMENT_GATEWAY_BUSY` (429): "we are saturated, try again shortly" is not "the gateway is down."
Both render as coded RFC 9457 `ProblemDetail`s and can never leak as a 500.

The 5.2 observation story: dial `PAYMENT_SANDBOX_UNAVAILABLE_RATE` up and watch
`/actuator/circuitbreakers` (three independent named breakers) move `CLOSED → OPEN → HALF_OPEN →
CLOSED`; hang the sandbox via `PAYMENT_SANDBOX_LATENCY_MILLIS` and prove the bulkhead by keeping a
second gateway serving throughout. Recovery is automatic — the half-open probes are real calls.

**Boot 4 note.** This stack is Spring Boot 4.1.0. Resilience4j's `resilience4j-spring-boot3`
starter ships a version verifier that deliberately refuses Boot 4
(`SpringBoot3Verifier` throws `IncompatibleSpringBootVersionException` and kills the context). We
exclude that one auto-configuration (`spring.autoconfigure.exclude`); the rest of the starter's
auto-configuration — the registries, the `resilience4j.*` property binding, and the actuator
endpoints — is verified to work on Boot 4.1, and `ResilienceConfigTest` keeps that verdict as a
regression test.

## The scenario to pray they ask
"Payment goes down during checkout — what happens?" Walk the layers: **timeout** (2 s, not 30) → **retry** with backoff + idempotency key (no double charge) → failures trip the **breaker** → users fall into the **fallback** (booking PENDING, seats briefly held, "we'll email your tickets") → on recovery the breaker half-opens and queued work drains → if it can't complete in time, the **saga compensation** releases the seats. Six concepts, one story.
