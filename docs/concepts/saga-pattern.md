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
3. Booking: mark CONFIRMED              C1. Booking: seats stay held; user retries
                                            until the 15-min hold lapses

                                        PAYMENT LANDS AFTER THE HOLD DIED
                                        C2. Booking: EXPIRED + publish rejection
                                        C3. Payment: refund in full
                                            (compensation — a refund, not a rollback)
```

## Two coordination styles — know the table cold

| | Choreography (events) | Orchestration (coordinator) |
|---|---|---|
| mechanism | each service reacts to the previous one's event (Booking emits `SeatsHeld`, Payment listens, emits `PaymentSucceeded`…) | an orchestrator (e.g. inside Booking) explicitly commands each step and decides what's next |
| coupling | loose — no one knows the whole flow | participants are simple; the orchestrator knows the flow |
| visibility | flow is implicit; "where is booking 42 stuck?" is hard | flow is explicit code + a state machine; easy to monitor |
| risk | cyclic event spaghetti as steps grow | orchestrator drifting into a god-service |
| use when | 2–3 steps, naturally event-shaped | 4+ steps, compensations, business-critical (payments!) |

## How we use it here — choreographed
There is no orchestrator. Booking never calls Payment; each service reacts to the other's events over `screenmaster-exchange`. `POST /bookings` creates `PENDING` and holds seats (the hard DB constraint). Payment pulls what it needs over the single synchronous hop in the flow — `GET /bookings/{id}/payability`, facts only, no judgement — then drives the gateway. Its webhook outcome becomes `PaymentSucceeded` / `PaymentFailed`, and Booking's `PaymentEventListener` → `BookingConfirmer` applies it.

We chose choreography over the orchestration the table above would suggest for a payment flow, and it is worth being honest about the trade: the flow is only three steps and genuinely event-shaped, and the gateway webhook is already an inbound event we cannot turn into a synchronous return value. The cost is exactly what the table warns about — no one object knows the whole saga, so "where is booking 42 stuck?" is answered by reading two services' logs.

**Saga state lives on the booking row** (`PENDING → CONFIRMED | EXPIRED | CANCELLED`), so a restart resumes from the database rather than from memory. The 15-minute `expiresAt` doubles as the saga timeout: `BookingExpirySweeper` expires stale `PENDING` bookings and frees the seats (a *gap* in the monolith that we had to build).

**The confirm is guarded, not assumed.** `confirmIfStillPending` is one conditional UPDATE — `WHERE id = ? AND status = PENDING AND expiresAt > now` — so a `PaymentSucceeded` that arrives after the hold lapsed matches zero rows instead of confirming a booking whose seats are gone. Zero rows is also what makes the listener idempotent under the relay's at-least-once delivery: a redelivered event updates nothing and re-reads `CONFIRMED`.

**The compensation is a refund.** When the money arrives too late, Booking publishes `BookingConfirmationRejected` carrying the `paymentId`, and Payment's `BookingConfirmationRejectedListener` issues a full refund — keyed `"reject-" + eventId` so the UNIQUE constraint *is* the dedupe. Nothing is rolled back, because the customer was really charged.

**Test it:** set `FAIL_RATE=0.3` on the fake payment service, fire 50 bookings, verify every failure compensated — **zero orphaned seat holds.** That's the talk track: "my saga survives a 30% payment failure rate with zero leaked seats — I verified with a script."

## How it connects to seat correctness
The double-booking guard (`count active bookings for these seats > 0 ⇒ reject`) stays a **strictly-consistent, local** DB check inside Booking. Strong consistency *inside* the boundary; eventual consistency (the saga, the events) *between* boundaries. Persisting saga state on the row is what lets a restarted service resume, and what lets the guarded UPDATE decide safely when two things reach the same booking at once.

## Interview lens
"Sagas: local transactions, each followed by an event; on failure, compensations undo the completed steps — a refund, not a rollback. We chose choreography because the flow is three event-shaped steps and the gateway webhook is already an inbound event; orchestration earns its keep at 4+ steps where an explicit, monitorable state machine beats loose coupling. The interesting part is the guarded step: confirming is one conditional UPDATE against `status = PENDING AND expiresAt > now`, so a payment that lands after the hold lapsed matches zero rows and triggers a refund instead of confirming seats we no longer own — and that same zero-row branch is what makes the consumer idempotent under at-least-once delivery. Eventual consistency between services; strict consistency where it matters, since seat uniqueness stays a hard constraint inside Booking."
