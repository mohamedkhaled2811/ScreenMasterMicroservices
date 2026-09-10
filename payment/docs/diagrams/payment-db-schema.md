# Payment · Database Schema

**Type:** high-level (data model) · **Scope:** `payment-db`, all six tables
**Files:** `payment-db-schema.html` (source) · `.svg`

## What it shows

Every table Payment owns, and — more usefully — **which constraint does the work** in each. This is
a service where the database is not passive storage: three unique constraints arbitrate races that
application code cannot win, and one table exists purely as an audit trail.

## The four domain tables

### `payments` — the obligation

One row per booking, created on first sight. `uq_payments_booking_id` guarantees that, not the
read that precedes it: two concurrent first-time requests both miss the read, both insert, and the
loser catches the violation and re-reads the winner's row. **The constraint is the guard; the read
is only an optimization.**

`booking_id` is a **plain column, not a foreign key** — Booking is a different service with a
different database. `amount` and `currency` are snapshotted from Booking's authoritative
`payability` answer at creation, so a later price change never rewrites a charge.

`refunded_amount` is what lets `PARTIALLY_REFUNDED` exist as a real state: a 1000 EGP payment
refunded 300 then 200 leaves 500 refundable, which a single `PAID → REFUNDED` flip cannot express.

### `payment_attempts` — one row per checkout session

**The obligation/attempt split is the single most important thing on this diagram.** The monolith
had one payments row and one status; conflating "what is owed" with "one try at settling it" is the
mistake this split prevents. A user who lets a checkout page lapse and clicks "Pay Again" gets a
second *attempt* on the same *payment* — which is why an expired attempt is not a payment failure,
and why nothing is announced when one is swept away.

Four indexes, each earning its place:

| Index | Job |
|---|---|
| `uq_active_attempt_per_payment` | **partial unique** (`WHERE status = 'PENDING'`) — at most one live attempt per payment |
| `uq_payment_attempts_gateway_session` | the webhook hot path: find the attempt a delivery belongs to |
| `uq_payment_attempts_idempotency_key` | the key forwarded to the gateway, unique per attempt |
| `idx_payment_attempts_status_expires_at` | what the expiry sweeper scans |

The partial unique index is what makes a double-clicked "Pay" button harmless. Two concurrent
requests both try to insert; the database rejects the loser, which is told to retry, and the retry
finds the winner's live attempt and reuses it. `PaymentWriter` catches that violation and renders it
as a **coded 400**, because it is a correct outcome, not a 500.

It is raw SQL in the changelog because Liquibase's `createIndex` has no portable `WHERE` clause, and
`dbms: postgresql` keeps it off H2 (which has no partial indexes at all) — the hermetic tests assert
the service-level behaviour instead, and Postgres carries the real guarantee.

### `refunds` — a row per refund, because partial refunds are real

`uq_refunds_idempotency_key` **is** the dedupe for the auto-refund. `BookingConfirmationRejected`
carries an `eventId`; the listener derives the key as `"reject-" + eventId`, so a redelivered
rejection produces the same key and the constraint rejects the second insert.

That is why there is **no `processed_events` table anywhere in this service.** The constraint you
already need for correctness is also the one that makes the consumer idempotent.

`gateway_refund_id` is what refund webhooks correlate on — never the attempt-session lookup the
payment path uses, because Stripe's `charge.refunded` carries no checkout-session id at all.

### `webhook_events` — the evidence store

Every delivery is kept: `gateway`, `event_id`, `event_type`, the raw `payload`, the `headers` (with
secret-bearing values scrubbed), `signature_valid`, the correlated `payment_attempt_id`,
`processing_status`, `received_at`, and `processed_at`.

`uq_webhook_events_gateway_event_id` — `UNIQUE (gateway, event_id)` — **is** the dedupe, and the
insert *is* the check. A read-then-insert would leave a window in which two concurrent deliveries of
the same event both pass; letting the insert fail closes it at the only place it can be closed.

Forged deliveries are stored too, with `signature_valid = false`, in their own committed transaction
*before* the 400 goes back. A run of them from one source is an attack signature, and discarding the
delivery would discard the evidence.

`processing_status` is what makes the crash-safety re-run possible: a `RECEIVED` row with no applied
outcome means the first attempt died between the two transactions, and the next delivery re-runs the
apply.

Its four values are not interchangeable. `IGNORED` is a *decision* — an event type we do not act on,
an unknown session, or an attempt already terminal — and is answered 200 because it is not the
gateway's problem. `FAILED` means processing **threw**, and the row is the evidence needed to replay
the delivery once the bug is fixed. Collapsing the two would hide real bugs inside a status that
means "working as intended".

## The infrastructure table

### `outbox`

`id`, `event_type`, `aggregate_id`, `routing_key`, `payload`, `created_at`, `published_at`.

`published_at IS NULL` means pending — and that count is the lag signal worth alerting on.

The point is *when* a row is written, not what is in it: an outbox row commits in the **same
transaction** as the business state change it announces. `PAID` and its `PaymentSucceeded` are one
atomic write, so the dual-write problem — state without event, or event without state — cannot
occur. `OutboxRelay` then drains it publish-then-mark, claiming rows with
`SELECT … FOR UPDATE SKIP LOCKED` so overlapping ticks never block on each other.

## The one table that is not ours

### `sandbox_charges`

The **fake third party's own ledger**, parked in `payment-db` only because the lab has nowhere else
to put it. `session_id` (unique), `gateway_payment_id`, `outcome`, `failure_reason`, `created_at`.

Drawn set apart on the diagram deliberately: **no payment domain code ever reads it.** Only
`SandboxGateway.fetchStatus` and `SandboxCheckoutController` touch it. Payment learns the outcome
the same way it learns Stripe's — from a signed webhook, or from reconciliation asking
`fetchStatus`.

If this table were merged into the domain, the sandbox would stop being a gateway and become an
in-process shortcut, and the reconciliation demo would prove nothing.

## The enum vocabularies

All persisted as `EnumType.STRING` — never ordinals (a flagged monolith gap).

| Enum | Values |
|---|---|
| `PaymentStatus` | `PENDING`, `PAID`, `FAILED`, `CANCELLED`, `REFUNDED`, `PARTIALLY_REFUNDED` |
| `PaymentAttemptStatus` | `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`, `CANCELLED` |
| `RefundStatus` | `PENDING`, `SUCCEEDED`, `FAILED` |
| `PaymentGatewayType` | `STRIPE`, `PAYMOB`, `SANDBOX` |
| `WebhookProcessingStatus` | `RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED` |

Note that `PaymentStatus` and `PaymentAttemptStatus` are **different enums on purpose**, even
though both start at `PENDING`. An attempt reaching `EXPIRED` leaves its payment `PENDING` — the
session died, the obligation did not.

## Patterns → concepts

- One database per service, no cross-service FKs —
  [database-per-service](../../../docs/concepts/database-per-service.md)
- Migrations and `ddl-auto=validate` — [liquibase](../../../docs/concepts/liquibase.md)
- The outbox table's role — [transactional-outbox](../../../docs/concepts/transactional-outbox.md)
- Constraints as dedupe — [idempotent-consumer](../../../docs/concepts/idempotent-consumer.md)
- Entity mapping and lazy loading — [jpa-and-hibernate](../../../docs/concepts/jpa-and-hibernate.md)

## Code anchors

- `payment/src/main/resources/db/changelog/changes/002-rebuild-payment-domain.yaml`
- `payment/src/main/resources/db/changelog/changes/003-create-outbox.yaml`
- `payment/src/main/resources/db/changelog/changes/004-create-sandbox-charges.yaml`
- `payment/src/main/java/com/gr74/payment/model/Payment.java`
- `payment/src/main/java/com/gr74/payment/model/PaymentAttempt.java`
- `payment/src/main/java/com/gr74/payment/model/WebhookEvent.java`

## Regenerating

The `.svg` is extracted from the HTML (first `<svg>` node, with a Google Fonts `@import` injected
and `&` XML-escaped). No `.png` — this checkout has no rasterizer.
