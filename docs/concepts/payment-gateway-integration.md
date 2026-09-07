# Payment gateway integration

> **Why this, here:** ScreenMaster's `payment` service integrates real gateways (Stripe, Paymob) in sandbox mode. Everything below exists because *money is the one thing you cannot roll back*. See [BUILD_PLAN.md Phase 3](../BUILD_PLAN.md) and the plan at [plans/real-payment-gateway/plan.mdx](../../plans/real-payment-gateway/plan.mdx).

## The one idea everything follows from

**A database transaction cannot span an external payment gateway.**

```java
@Transactional
public void pay() {
    updateBooking();      // local DB
    gateway.charge();     // EXTERNAL — not in the transaction
}                         // commit fails here -> customer charged, booking not confirmed
```

There is no rollback for "un-charge the customer." So instead of one atomic step we use: **idempotency** (a retry is not a second charge) + **webhooks** (the gateway tells us the truth, later) + **state transitions** (each step is a local transaction) + **reconciliation** (poll for what the webhook missed) + **compensation** (refund when the outcome can no longer be honored).

## Sessions, not charges

We never see a card number. The gateway hosts the payment form; we ask it to open a **checkout session** and hand the user a URL.

```
POST /payments -> create session -> return checkout_url -> user pays ON THE GATEWAY
```

This is a deliberate architectural choice with a compliance consequence: no PAN and no CVV ever enter ScreenMaster, which keeps PCI scope at its smallest (SAQ-A). The `PaymentGateway` port has **no `charge(card)` method** — the shape of the interface is what enforces this.

## Payment vs PaymentAttempt

The distinction that makes "Pay Again" safe:

| | Meaning | Cardinality |
|---|---|---|
| `Payment` | The **obligation**: "booking #1001 owes 300.00 EGP" | One per booking (`UNIQUE booking_id`) |
| `PaymentAttempt` | **One try** at settling it through one gateway session | Many per payment |

A user who abandons checkout and returns does **not** get a second `Payment` — they get a second `PaymentAttempt` on the same one. `Payment.status` stays the single answer to "is this booking paid?", while the messy retry history lives on the attempts.

A **partial unique index** (`ON payment_attempts (payment_id) WHERE status = 'PENDING'`) allows at most one live attempt, so two concurrent "Pay" clicks cannot open two gateway sessions.

## Two clocks

| Clock | Owner | On expiry |
|---|---|---|
| Booking hold (15 min) | Booking | Seats released. **Not recoverable** — book again. |
| Gateway session (shorter) | Payment | **Recoverable** — open a new attempt on the same Payment. |

At 20:06 with a booking valid to 20:15: session dead, seats still yours, "Pay Again" works. At 20:16: no new attempt — the seats may already be someone else's.

## Webhooks are the only path to PAID

The browser is never trusted to report a payment. The `return_url` the gateway redirects to is a UX convenience — it may never be hit, and it can be forged.

```
gateway --signed webhook--> Payment: verify -> store -> dedupe -> normalize -> update -> outbox
```

Rules that matter:

1. **Read the raw body bytes.** Signatures are computed over exact bytes; a Jackson round-trip reorders keys and breaks verification.
2. **Verify with a constant-time comparison** (`MessageDigest.isEqual`, not `String.equals`) — a timing leak gives up the signature byte by byte.
3. **Store the payload before processing**, in a `REQUIRES_NEW` transaction, so evidence survives a rolled-back business transaction.
4. **The `UNIQUE (gateway, event_id)` violation *is* the dedupe.** One statement both stores and detects redelivery; there is no window where an event is processed but unstored.
5. **Answer 200 to almost everything.** Unknown session, already processed, uninteresting type — all 200, because a gateway retries any non-2xx for hours. Reserve non-2xx for a failed signature (400) and a genuine internal fault (500, where a retry is what you want).

### Why store the raw payload

Beyond dedupe: settling a disputed payment with the actual bytes; replaying events through a *fixed* handler after a processing bug; backfilling a field you did not read at the time (fees, card brand, 3DS result); and keeping `signature_valid = false` rows as the audit trail of a forgery attempt. Treat stored payloads as data — no bodies in logs, ADMIN-only reads, and a retention window.

## The gateway abstraction

One port, many adapters, and **no gateway-specific `if/else` outside an adapter**:

```java
public interface PaymentGateway {
    PaymentGatewayType type();
    Set<String> supportedCurrencies();
    GatewaySession createSession(GatewaySessionRequest request);
    GatewayPaymentStatus fetchStatus(GatewayStatusQuery query);
    RefundResult refund(GatewayRefundRequest request);
    GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers);
}
```

A `GatewayRegistry` built from an injected `List<PaymentGateway>` maps type → adapter (see [spring-core-and-beans.md](spring-core-and-beans.md) for why Spring collects implementations that way). Adding a gateway is a new `@Component` plus an enum constant — no edits to `PaymentService`.

Each adapter **normalizes** its gateway's vocabulary into ours: Paymob's `success` and Stripe's `checkout.session.completed` both become `SUCCEEDED`. Nothing outside the adapter package ever sees a gateway's own words.

## Money

Never `double` or `float` — always `BigDecimal`, always with an ISO-4217 `currency` beside it. Gateways disagree on format: one wants `300.50`, another wants `30050` minor units. **That conversion belongs inside the adapter:**

```java
int exponent = Currency.getInstance(currency).getDefaultFractionDigits();
return amount.movePointRight(exponent)
             .setScale(0, RoundingMode.UNNECESSARY)  // refuse to silently round money
             .longValueExact();
```

`UNNECESSARY` is deliberate: an amount that cannot be expressed exactly in minor units is a bug upstream. Silent rounding is how reconciliation drifts by cents forever. Read the exponent rather than hardcoding ×100 — EGP and USD have 2 minor digits, JPY has 0, KWD has 3.

## Refunds are rows, not a status flip

`PAID → REFUNDED` cannot express a partial refund. A `refunds` child table can: 1000 EGP refunded 300 then 200 leaves 500 refundable, and `payment.status` becomes `PARTIALLY_REFUNDED` or `REFUNDED` from the running total. Check `Σ refunds ≤ payment.amount` under a row lock, key each refund with its own idempotency key, and only mark it `SUCCEEDED` when the refund's *own* webhook confirms.

## Reconciliation

Webhooks are not the only recovery mechanism. If we were down when one arrived, the gateway says `PAID` and we say `PENDING` forever. A scheduled job polls `fetchStatus()` for stale `PENDING` attempts and applies the result **through the same handler the webhook uses** (dedupe included), so a late webhook is a no-op. This is what makes the webhook path *recoverable* rather than *critical*.

## Security checklist

- Hosted checkout only — no PAN, no CVV, ever.
- Gateway secrets in env vars / `.env`, never committed, never sent to the frontend.
- Sandbox vs production credentials separated by profile; assert loudly on a live key outside production.
- Verify every webhook signature, constant-time, before parsing.
- Never log card data, full webhook bodies, or secret keys.
- HTTPS everywhere; webhook URLs are https-only (locally: `stripe listen` / an ngrok tunnel).
- Refund and payment-admin endpoints require an admin role.
- The client sends a booking id and a gateway choice — **never an amount**. Payment reads the amount from Booking.

## Related

[saga-pattern.md](saga-pattern.md) · [transactional-outbox.md](transactional-outbox.md) · [idempotent-consumer.md](idempotent-consumer.md) · [resilience-patterns.md](resilience-patterns.md) · [error-handling-problemdetail.md](error-handling-problemdetail.md) · [database-per-service.md](database-per-service.md)
