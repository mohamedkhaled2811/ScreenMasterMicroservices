# Transactional outbox: reliable event publishing

## What it is
Write the event you want to publish into an `outbox` table **in the same local transaction as the business change**; a separate relay reads the outbox and delivers to the broker afterward. It makes "update the DB *and* publish a message" atomic without a distributed transaction.

## Why it exists — the dual-write problem
Inside one saga step, Booking must (a) commit `status = CONFIRMED` to Postgres and (b) publish `BookingConfirmed` to RabbitMQ. Two systems, **no shared transaction**. Crash between them and you get one of two bad outcomes:
- DB says CONFIRMED, but **no event** ever sends → no tickets emailed.
- Event announces a booking that then **rolled back** → a ghost confirmation.

That's the **dual write**, and it's the sneakiest bug in event-driven systems.

## The fix
```sql
BEGIN;
  UPDATE bookings SET status = 'CONFIRMED' WHERE id = 42;
  INSERT INTO outbox (id, aggregate_id, event_type, payload, created_at)
  VALUES (gen_random_uuid(), 42, 'BookingConfirmed', '{"bookingId":42,...}', now());
COMMIT;   -- atomic: both rows, or neither
```
The event is now as durable and consistent as the business change — they commit together or not at all.

A **relay** then publishes it, separately:
```sql
-- a @Scheduled poller (or, in production, CDC like Debezium tailing the WAL):
SELECT * FROM outbox
WHERE published_at IS NULL
ORDER BY created_at
FOR UPDATE SKIP LOCKED          -- so multiple relay instances don't grab the same rows
LIMIT 100;
-- publish each to RabbitMQ → UPDATE outbox SET published_at = now()
```

## At-least-once → you must add idempotency
The relay can crash *after* publishing but *before* marking `published_at`, so on restart it republishes. Delivery is therefore **at-least-once** — every consumer must be idempotent (see [idempotent-consumer.md](idempotent-consumer.md)). The aphorism: *exactly-once delivery is impossible; exactly-once **processing** is an application-level achievement* (at-least-once delivery + idempotency).

## How we use it here (field guide M4)
On `CONFIRMED`, Booking writes `BookingConfirmed` to an `outbox` table in the same transaction. A `@Scheduled` relay claims rows with `FOR UPDATE SKIP LOCKED` and publishes to RabbitMQ's `bqueue` (the schema doc's reserved-but-unused booking queue — a ready seam). Notification consumes and "sends" the email.

**Prove it:** kill the relay mid-batch, restart, and confirm exactly one email per booking despite redelivery; run two notification instances (`--scale notification=2`) and confirm no double-send. Talk track: "I implemented outbox + idempotent consumer and tested duplicate delivery on purpose."

`SKIP LOCKED` is the same Postgres trick used for safe concurrent queue polling — it lets multiple relay workers each grab a disjoint batch without blocking.

## Interview lens
"Dual write = updating the DB and publishing as two separate operations; a crash between them leaves them disagreeing. Outbox: write the event into an outbox table in the same local transaction (atomic by construction), then a relay — a poller, or CDC like Debezium — publishes it. Delivery becomes at-least-once, so consumers dedupe by event id, atomically with their own work."
