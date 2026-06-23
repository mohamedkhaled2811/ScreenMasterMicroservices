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

## How we use it here (field guide M4)
Notification dedupes `BookingConfirmed` via a `processed_events` table before "sending" the email. We prove it by forcing redelivery (kill/restart the relay) and by scaling notification to 2 instances — exactly one email-log per booking either way.

The schema doc also flags two existing spots that **must** become idempotent once split: the **PayPal webhook** (dedupe by `transactionId`) and any **RabbitMQ consumer** (dedupe by message id).

## Interview lens
"Exactly-once delivery is impossible; exactly-once *processing* is achievable: at-least-once delivery plus idempotent consumers. Mechanically — events carry an id, the consumer inserts it `ON CONFLICT DO NOTHING` and does the work in the same transaction, so a duplicate is a clean no-op. Better still, design the operation to be naturally idempotent (unique constraints, UPSERTs, absolute-state updates)."
