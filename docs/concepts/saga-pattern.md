# Saga pattern: transactions across services

## What it is
A **saga** is a sequence of *local* transactions — one per service — where each step's failure triggers **compensating transactions** that semantically undo the completed steps, in reverse order. It replaces the cross-service ACID transaction you no longer have.

## Why it exists
Confirming a booking touches two services: Booking (reserve seats) and Payment (charge). There is no `BEGIN … COMMIT` across two databases.

The classical answer, **two-phase commit (2PC)**, is *avoided* in microservices — know why in one breath: a coordinator asks everyone to *prepare*, then *commit*, but participants **sit blocked holding locks** while waiting, and if the coordinator dies they're stuck *in-doubt*. Availability collapses exactly when things go wrong — the opposite of what microservices want — and most brokers/HTTP services don't speak 2PC anyway.

So: trade atomicity for availability, and handle failure with compensation. **A compensation is not a rollback** — if the money was really captured, the compensation is a real *refund*.

## The booking saga
```
HAPPY PATH                              FAILURE AT STEP 2
1. Booking: create PENDING, hold seats  1. Booking: create PENDING, hold seats
2. Payment: charge card      ──OK──▶    2. Payment: charge FAILS  ──✗──▶
3. Booking: mark CONFIRMED              C1. Booking: release seats, mark CANCELLED
4. Notification: send tickets               (compensation — semantic undo)
```

## Two coordination styles — know the table cold

| | Choreography (events) | Orchestration (coordinator) |
|---|---|---|
| mechanism | each service reacts to the previous one's event (Booking emits `SeatsHeld`, Payment listens, emits `PaymentSucceeded`…) | an orchestrator (e.g. inside Booking) explicitly commands each step and decides what's next |
| coupling | loose — no one knows the whole flow | participants are simple; the orchestrator knows the flow |
| visibility | flow is implicit; "where is booking 42 stuck?" is hard | flow is explicit code + a state machine; easy to monitor |
| risk | cyclic event spaghetti as steps grow | orchestrator drifting into a god-service |
| use when | 2–3 steps, naturally event-shaped | 4+ steps, compensations, business-critical (payments!) |

## How we use it here (field guide M3 — orchestrated)
`POST /bookings` → Booking creates `PENDING` and holds seats (the hard DB constraint) → calls Payment **synchronously** → on approval mark `CONFIRMED`; on decline run the compensation (release seats, mark `CANCELLED`).

**Persist saga state on the booking row** (`PENDING → CONFIRMED | CANCELLED`) so a crashed orchestrator resumes from the DB. The 15-minute `expiresAt` doubles as the saga timeout: a **sweeper job** expires stale `PENDING` bookings and frees seats (this sweeper is a *gap* in the monolith — we must build it).

**Test it:** set `FAIL_RATE=0.3` on the fake payment service, fire 50 bookings, verify every failure compensated — **zero orphaned seat holds.** That's the talk track: "my saga survives a 30% payment failure rate with zero leaked seats — I verified with a script."

## How it connects to seat correctness
The double-booking guard (`count active bookings for these seats > 0 ⇒ reject`) stays a **strictly-consistent, local** DB check inside Booking. Strong consistency *inside* the boundary; eventual consistency (the saga, the events) *between* boundaries. The orchestrated saga's per-step persistence is what lets it resume and compensate reliably.

## Interview lens
"Sagas: local transactions, each followed by an event/command; on failure, compensations undo completed steps in reverse — a refund, not a rollback. Choreography for 2–3 event-shaped steps; orchestration for payment-critical flows because the state machine is explicit and monitorable. Result: eventual consistency between services, strict consistency where it matters (seat uniqueness stays a hard constraint inside Booking)."
