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
Add the Resilience4j stack to Booking's payment client (timeout 2 s, 3 retries with backoff, breaker, fallback = stay PENDING + queue). Then `docker stop payment` during a load loop and narrate: first requests eat the timeout → breaker opens (watch `/actuator/circuitbreakers`) → subsequent requests fail fast into the fallback → `docker start payment` → breaker half-opens → traffic resumes → queued bookings drain.

## The scenario to pray they ask
"Payment goes down during checkout — what happens?" Walk the layers: **timeout** (2 s, not 30) → **retry** with backoff + idempotency key (no double charge) → failures trip the **breaker** → users fall into the **fallback** (booking PENDING, seats briefly held, "we'll email your tickets") → on recovery the breaker half-opens and queued work drains → if it can't complete in time, the **saga compensation** releases the seats. Six concepts, one story.
