# Idempotent consumer

## What it is
A consumer that **survives receiving the same event twice** with no extra effect — process `BookingConfirmed` once, or ten times, and exactly one email goes out. "Idempotent" = doing it again changes nothing.

## Why it exists
The [outbox](transactional-outbox.md) relay delivers **at-least-once**: it can publish, then crash before marking the row done, and republish on restart. Brokers also redeliver on consumer crashes or missed acks. So duplicates are *normal*, not exceptional. A consumer that isn't idempotent sends two emails, double-credits a wallet, or double-applies a state change.

## The standard mechanics
Events carry a unique **event id**. The consumer records processed ids and skips repeats — **atomically with its own work**, in one local transaction:

```sql
BEGIN;
  INSERT INTO processed_events (event_id) VALUES ($1)
  ON CONFLICT DO NOTHING;          -- a second delivery inserts 0 rows
  -- ONLY if the insert actually inserted a row: do the work
  -- (send the email / update the read model) in this same transaction.
COMMIT;                            -- → exactly-once *effect*
```
Because the dedupe insert and the side effect share a transaction, you can't end up "recorded as processed but email never sent" or vice versa.

## Natural idempotency is even better
Sometimes the operation is idempotent by design and you need no dedupe table:
- A `UNIQUE(showtime_id, seat_id)` constraint means a duplicated "hold this seat" insert just fails cleanly the second time — a no-op.
- An UPSERT into a read model keyed by id (`INSERT … ON CONFLICT DO UPDATE`) converges to the same state no matter how many times it runs.
- Setting `status = CONFIRMED` (vs *incrementing* something) is naturally idempotent.

Prefer natural idempotency when the data model allows it; fall back to the `processed_events` table when it doesn't.

## The synchronous twin: request idempotency keys
The same problem shows up on **synchronous HTTP**, not just message consumers. A client (or a [saga](saga-pattern.md)) retries a `POST` after a timeout — but the first call may have *succeeded* and the response just got lost. Without a guard, the retry does the work twice (a double charge).

The fix is the same shape, with a **client-supplied key** instead of a broker's event id:
- The caller sends an `Idempotency-Key` header (a UUID it reuses across retries of the *same* logical request).
- The server stores results keyed by it, with a **`UNIQUE` constraint** on the key column.
- A repeat key **replays the stored result** instead of redoing the work.

```text
read by key → found?  → return the stored result (no re-charge)
            → not found → do the work, INSERT the row
                          → UNIQUE violation? a concurrent twin won; re-read & return its row
```
The `UNIQUE` constraint — not the pre-read — is the real guard: two concurrent first-time requests both miss the read, both do the work, but only one row inserts; the loser catches the violation and returns the winner's result. (This is the "natural idempotency via a unique constraint" case above, applied to a request.)

## How we use it here (field guide M4 + 1.2)
**Payment (1.2)** is the synchronous twin: `POST /payments` takes an `Idempotency-Key`, and `payments.idempotency_key` is `UNIQUE`, so a retried charge replays the original outcome (approved/declined) and never charges twice. The service pre-reads the key, then relies on the constraint to settle the concurrent-duplicate race — see `payment/.../PaymentService.java`.

**Notification (M4)** is the asynchronous case: it dedupes `BookingConfirmed` via a `processed_events` table before "sending" the email. We prove it by forcing redelivery (kill/restart the relay) and by scaling notification to 2 instances — exactly one email-log per booking either way.

The schema doc also flags two existing spots that **must** become idempotent once split: the **PayPal webhook** (dedupe by `transactionId`) and any **RabbitMQ consumer** (dedupe by message id).

## Interview lens
"Exactly-once delivery is impossible; exactly-once *processing* is achievable: at-least-once delivery plus idempotent consumers. Mechanically — events carry an id, the consumer inserts it `ON CONFLICT DO NOTHING` and does the work in the same transaction, so a duplicate is a clean no-op. Better still, design the operation to be naturally idempotent (unique constraints, UPSERTs, absolute-state updates)."
