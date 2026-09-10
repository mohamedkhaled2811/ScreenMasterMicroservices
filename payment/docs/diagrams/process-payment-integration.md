# Payment · Cross-Service Reads

**Type:** high-level (process / swimlane) · **Scope:** the `payment` service and its edges to `booking`
**Files:** `process-payment-integration.html` (source) · `.svg` · `.png`

## What it shows

Every place Payment crosses the service boundary. Payment and Booking never share a transaction, a
database, or a foreign key — they meet in exactly **three** places, and the diagram colours each one
differently because each answers failure differently.

| # | Crossing | Direction | Colour |
|---|---|---|---|
| 1 | `GET /bookings/{id}/payability` | sync, out | orange (solid) |
| 2 | `PaymentSucceeded` / `PaymentFailed` | async, out | purple (dashed) |
| 3 | `BookingConfirmationRejected` | async, **in** | red (dashed) |

## Crossing 1 — the one synchronous read, and why it fails closed

`BookingClient.fetchPayability()` is the only sync call on the payment write path. It exists because
**Payment must never trust the client for the amount** — a browser that could name its own price would be
the whole security model gone. Booking owns the booking, so Booking owns the price.

It returns `BookingPayability`: a slice of **facts** (owner, status, hold deadline, authoritative amount,
currency) with no judgement in it. `PaymentService.createSession` applies the judgement itself — six guards,
in order: fetchable → owner matches → `PENDING` → seat hold still live → not already settled → gateway
registered for that currency.

**Contrast this with Booking's `titlesByIds`,** which degrades to `movieTitle: null` when Catalog is down.
Both are cross-service reads; they fail in opposite directions on purpose:

| | Booking → Catalog (`titlesByIds`) | Payment → Booking (`fetchPayability`) |
|---|---|---|
| On 404 | id absent from the map | reject — `404 PAYMENT_BOOKING_NOT_FOUND` |
| On outage | **degrade** to null titles | **throw** — `503 PAYMENT_BOOKING_SERVICE_UNAVAILABLE` |
| Why | a read is more useful partial than absent | money must never be guessed at |

That is the lesson of the diagram: *the right failure policy depends on what the call is for.* The
404/outage split itself is shared — "no such booking" is definitive and retrying will not change it, while
an outage might resolve later.

## Crossing 2 — events out, through the outbox

The webhook is the only road to `PAID`. The state change **and** the outbox row are written in one local
transaction (the olive node), so there is no dual write to lose. `OutboxRelay` then publishes to
`screenmaster-exchange` on `payment-succeeded-key` / `payment-failed-key` — **publish-then-mark**, so a
crash between the two republishes rather than strands the event. Delivery is at-least-once, which is safe
because every consumer is idempotent.

**Payment never calls Booking to confirm a booking.** The saga is choreographed, not orchestrated.

## Crossing 3 — the event in, and the compensation

Booking's confirm is a conditional `UPDATE … WHERE status = PENDING`. When it matches **zero rows** the
seat hold already died — the user sat on the gateway's page too long — and Booking emits
`BookingConfirmationRejected`.

There is nothing to roll back: **the customer has already been charged.** So the saga compensates.
`BookingConfirmationRejectedListener` is a thin shell over `RefundService.requestRefund`, for the full
remaining amount, with the idempotency key derived from the event id (`"reject-" + eventId`).

That derived key is what makes a redelivery harmless with **no `processed_events` table anywhere** — the
`UNIQUE idempotency_key` constraint *is* the dedupe. The listener takes no transaction and catches nothing:
a throw means no ack and a redelivery, which is safe precisely because of that key.

## Patterns → concepts

- **API composition (sync read across a boundary)** → [concepts/service-decomposition-ddd.md](../../../docs/concepts/service-decomposition-ddd.md)
- **Transactional outbox** → [concepts/transactional-outbox.md](../../../docs/concepts/transactional-outbox.md)
- **Saga / compensating transaction** → [concepts/saga-pattern.md](../../../docs/concepts/saga-pattern.md)
- **Idempotent consumer** → [concepts/idempotent-consumer.md](../../../docs/concepts/idempotent-consumer.md)

## Code anchors

- `payment/src/main/java/com/gr74/payment/client/BookingClient.java` — crossing 1; the fail-closed policy lives in its catch blocks
- `payment/src/main/java/com/gr74/payment/client/BookingPayability.java` — the narrow facts-only slice
- `payment/src/main/java/com/gr74/payment/service/PaymentService.java` — the six guards, in order
- `payment/src/main/java/com/gr74/payment/webhook/WebhookProcessor.java` — the only road to `PAID`
- `payment/src/main/java/com/gr74/payment/outbox/OutboxRelay.java` — crossing 2; publish-then-mark
- `payment/src/main/java/com/gr74/payment/messaging/BookingConfirmationRejectedListener.java` — crossing 3
- `payment/src/main/java/com/gr74/payment/service/RefundService.java` — the compensation, idempotent on the derived key
- `payment/src/main/java/com/gr74/payment/config/RabbitConfig.java` — routing keys + `payment-booking-events-queue`

## Deliberately out of scope

The three gateways behind the `PaymentGateway` port (see `architecture-payment-service.md`), the
reconciliation sweep that recovers a webhook that never came, and Booking's own side of the saga
(see [`../../../booking/docs/diagrams/process-booking-payment-saga.md`](../../../booking/docs/diagrams/process-booking-payment-saga.md)).

## Regenerating

```bash
# PNG (needs playwright + chromium)
python raster.py process-payment-integration.html process-payment-integration.png 2
```
