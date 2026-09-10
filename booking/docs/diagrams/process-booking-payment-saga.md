# Booking · Payment Saga

**Type:** process (swimlane) · **Scope:** the `booking` ↔ `payment` choreography over RabbitMQ
**Files:** `process-booking-payment-saga.html` (source) · `.svg` · `.png`

![Booking–payment saga](process-booking-payment-saga.png)

## What to learn here

There is no distributed transaction. Booking and Payment each commit only their own
database, and the saga holds together as a chain of idempotent, guarded steps connected by
events. The unhappy path is not a rollback — the customer has already been charged, so the
system compensates with an automatic refund.

Every dashed arrow on the diagram crosses the service boundary through the shared topic
exchange `screenmaster-exchange`. The single solid blue arrow — the payability check — is
the only synchronous hop in the whole saga.

## The walk

1. **HOLD** — `POST /bookings` runs `BookingService.create`: the booking row goes PENDING
   with a 15-minute seat hold (`expiresAt`). Seats are held, not sold, and the hold clock
   starts running immediately.
2. **SYNC** — `POST /payments` runs `PaymentService`, which first calls Booking
   synchronously: `BookingClient` → `GET /bookings/{id}/payability`, returning a
   `BookingPayability` of facts (status, deadline, authoritative amount) with no judgement.
3. **PAY** — `PaymentSessionFactory` resolves a gateway through `GatewayRegistry`
   (`SandboxGateway` / `StripeGateway` / `PaymobGateway`) and opens a checkout session.
   The user pays at the gateway, outside both services.
4. **HOOK** — the gateway calls back: `POST /payments/webhooks/{gateway}` →
   `WebhookController` → `WebhookProcessor`. The signature is verified and the delivery is
   stored in `webhook_events`, where `UNIQUE (gateway, event_id)` is the dedupe.
5. **ONE COMMIT** — `WebhookWriter` commits the business change (payment → PAID) and the
   `outbox_messages` row in the same transaction: one commit, no dual write.
6. **RELAY** — `OutboxRelay` (`@Scheduled`) drains the outbox publish-then-mark: it
   publishes `PaymentSucceeded` on `payment-succeeded-key`, then marks the row. A crash
   between the two re-publishes on the next tick, so delivery is at-least-once and every
   consumer must be idempotent.
7. **CONFIRM** — Booking's `booking-payment-events-queue` receives the event;
   `PaymentEventListener` (a thin AMQP shell) dispatches on the received routing key into
   `BookingConfirmer.confirmFromPayment`.

   **What the race is.** Two things can touch this booking at the same moment: *this*
   confirm, arriving from Payment, and the `BookingExpirySweeper`, which flips any hold
   older than 15 minutes to EXPIRED. If both read first and wrote second, they could both
   decide they won — and the seats would be sold and expired at once.

   Nothing in the application code arbitrates that. Instead the decision is pushed into
   the database as a single conditional UPDATE (`confirmIfStillPending`), whose WHERE
   clause carries the preconditions:

   ```sql
   UPDATE bookings SET status = 'CONFIRMED', payment_status = 'PAID'
    WHERE id = ? AND status = 'PENDING' AND expires_at > now
   ```

   The row lock makes it atomic, so exactly one writer can match. The **row count is the
   verdict** — no follow-up read, no lock held across a network call:

   - **1 row updated** — we won the race. The booking is CONFIRMED and PAID, and on commit
     `BookingEventPublisher` (an `AFTER_COMMIT` listener, so the event fires if and only if
     the confirm actually committed) publishes `BookingConfirmed` on
     `booking-confirmed-key`.
   - **0 rows updated** — we lost, or there was nothing to do. Re-reading the row says
     which: already CONFIRMED means this event is a redelivery and the correct action is
     nothing; EXPIRED or CANCELLED means the money arrived after the hold died, and the
     saga compensates (step 8).

   That "0 rows" branch is also what makes the consumer idempotent: replaying the same
   event can never double-confirm, because the second attempt matches no rows.
8. **REFUND (compensation)** — the same conditional UPDATE matches zero rows and the
   booking re-reads EXPIRED or CANCELLED: the money arrived after the seat hold died.
   Booking publishes `BookingConfirmationRejected` on
   `booking-confirmation-rejected-key`, carrying the `paymentId` so Payment refunds with
   no lookup. Payment's `BookingConfirmationRejectedListener` calls
   `RefundService.requestRefund` for the full amount with the derived idempotency key
   `"reject-" + eventId`; the `UNIQUE idempotency_key` constraint is the dedupe.

`BookingConfirmed` is published to the exchange for whoever binds to it; no service
subscribes today, which is why the diagram shows that arrow ending at the exchange rather
than at a consumer.

Two branches stay small on purpose: `PaymentFailed` (`payment-failed-key`) only reaches
`BookingConfirmer.mirrorFailure`, which mirrors a display field while the seats stay held
— the user may retry until the hold lapses, and nothing is decided from `paymentStatus`.
And a redelivered `PaymentSucceeded` landing on an already-CONFIRMED booking matches zero
rows: an idempotent no-op.

## Patterns → concepts

- Saga (choreography) and the compensating refund —
  [saga-pattern](../../../docs/concepts/saga-pattern.md)
- The one-commit outbox write and the publish-then-mark relay —
  [transactional-outbox](../../../docs/concepts/transactional-outbox.md)
- Redelivery-safe confirms, upserts, and refunds —
  [idempotent-consumer](../../../docs/concepts/idempotent-consumer.md)
- The exchange, queues, bindings, and routing keys —
  [rabbitmq](../../../docs/concepts/rabbitmq.md)
- Checkout sessions, gateway callbacks, and signature verification —
  [payment-gateway-integration](../../../docs/concepts/payment-gateway-integration.md)

## Code anchors

- `booking/src/main/java/com/gr74/booking/service/BookingConfirmer.java`
- `booking/src/main/java/com/gr74/booking/messaging/PaymentEventListener.java`
- `booking/src/main/java/com/gr74/booking/messaging/BookingEventPublisher.java`
- `booking/src/main/java/com/gr74/booking/config/RabbitConfig.java`
- `payment/src/main/java/com/gr74/payment/outbox/OutboxRelay.java`
- `payment/src/main/java/com/gr74/payment/service/WebhookWriter.java`
- `payment/src/main/java/com/gr74/payment/messaging/BookingConfirmationRejectedListener.java`
- `payment/src/main/java/com/gr74/payment/service/RefundService.java`

## Regenerating

```bash
# PNG (needs playwright + chromium; SYSTEM python only)
/usr/bin/python3 /tmp/raster.py booking/docs/diagrams/process-booking-payment-saga.html \
                                booking/docs/diagrams/process-booking-payment-saga.png 2
```
