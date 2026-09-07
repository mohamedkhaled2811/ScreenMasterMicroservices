# Pessimistic locking: check-then-insert under a row lock

## What it is
`SELECT ... FOR UPDATE` (JPA: `@Lock(PESSIMISTIC_WRITE)`) takes a database row lock that is held
until the transaction commits. Any other transaction locking the same row **waits** — so a
check and the write that depends on it happen as one serialized unit, with no interleaving.

## Why it exists — the invariant a constraint cannot express
Payment's refund invariant is `SUM(SUCCEEDED + PENDING refunds) ≤ payment.amount`. That sum spans
many rows, so no `CHECK` constraint can enforce it — and read-then-insert without a lock races:
two concurrent full refunds both read "nothing refunded yet", both pass, both insert, and the
customer is refunded twice. The `UNIQUE idempotency_key` guard cannot help either — the two
refunds carry *different* keys.

## The fix (RefundService tx 1)
```java
Payment payment = payments.lockById(paymentId);   // SELECT ... FOR UPDATE — others queue here
// check the invariant against committed rows, then INSERT the PENDING refund:
if (inFlight.add(requested).compareTo(payment.getAmount()) > 0) throw EXCEEDS_REMAINING;
refunds.save(new Refund(amount, reason, key));
COMMIT;   // the lock releases here; the waiter re-reads and sees this row
```
The loser does not fail with a deadlock — it **blocks, then re-reads**, and the now-visible
winner's row makes its own check fail with `PAYMENT_REFUND_EXCEEDS_REMAINING`. Blocking then
re-checking is the whole technique; the lock without the re-read would be theater.

## The rules that come with it
- **Never hold the lock across I/O.** The gateway call sits *between* tx 1 (lock → check →
  insert PENDING) and tx 2 (record the answer). A lock held across a network call turns every
  gateway slowdown into lock contention — and a gateway timeout into a stuck row.
- **Count PENDING, not just SUCCEEDED.** An in-flight refund constrains while its outcome is
  unknown (conservative on money); only FAILED drops out, because the gateway rejected it.
- **Pessimistic vs optimistic:** `@Version` retries suit low-contention updates (a booking edited
  twice). Money under concurrency wants *serialization*, not retries — hence the pessimistic
  lock, scoped to one payment row so unrelated payments never contend.
