# Notification · Service Architecture

**Type:** high-level (architecture) · **Scope:** the `notification` service, end to end
**Files:** `architecture-notification-service.html` (source) · `.svg`

## What it shows

What the Notification service **is** — the one service in the repo with **no HTTP API**. Its only
inputs are RabbitMQ messages; its only outputs are emails. The diagram is the whole consume path,
left to right, plus the two things that make it interesting: the idempotent-consumer **claim** and
the honest residual gap that claim cannot close.

Read it with this question in your head: *where does exactly-once actually come from?* The answer is
one raw `INSERT` in the middle of the picture.

## The spine (top row)

The input path, drawn left to right:

| Hop | Arrow | What actually happens |
|---|---|---|
| **Booking** → **RabbitMQ** | dashed, `PUBLISH` | Booking fires `BookingConfirmed` / `BookingConfirmationRejected` into the shared topic exchange `screenmaster-exchange`, knowing **nothing** about who listens. Adding Notification required **zero producer changes** — that is the point of the topic exchange. |
| **RabbitMQ** → **BookingEventListener** | dashed, `DELIVER` | Notification owns the durable queue `notification-booking-events-queue` and **both bindings** (`booking-confirmed-key`, `booking-confirmation-rejected-key`). One queue, two routing keys, one listener. |
| **BookingEventListener** → **NotificationService** | solid, `DISPATCH` | A deliberately thin AMQP shell. It receives a `Map<String,Object>` (not typed records — Booking's outbox relay publishes maps, so `__TypeId__` says `LinkedHashMap` and typed params could never bind), switches on the received routing key, and converts to the records Notification owns. It also **rebuilds the trace** from the `traceparent` header the relay stamped on the message — the outbox hop means the trace was never propagated automatically, so it is re-joined here as a child span and the traceId pushed to MDC in a try/finally. |

## The focal node: `NotificationService.claim()`

Coral because it is the **only place a real invariant is defended in this service**. The service is a
plain `@Transactional` class — deliberately *not* the listener itself, so it is unit-testable without
a broker. Its two methods (`processBookingConfirmed`, `processBookingConfirmationRejected`) run the
same two steps, in the same order:

**Claim, then send.** `claim(eventId, eventType)` does a raw `INSERT` into `processed_events`, where
`event_id` is an **ASSIGNED primary key** — the inbound event id (Booking's outbox row id), never one
Notification mints. The insert either creates the row (this consumer won the event) or throws
`DataIntegrityViolationException` (someone already claimed it), which the service catches, marks the
transaction rollback-only, and returns `false` — **ack-and-no-op**.

There is deliberately **no `existsById(...)` check first**. That read-then-insert has a window where
two concurrent consumer instances both pass the check and both send. The PK constraint arbitrates,
not application code. The `--scale notification=2` demo exists to prove this impossible.

**Ordering is load-bearing.** The Keycloak lookup happens *after* the claim on purpose: if the IdP is
down, the transaction rolls back taking the claim with it, and the broker redelivers — so the retry
re-claims cleanly. Looking the address up *before* the claim would burn the claim on an outage
without sending anything.

**The honest residual.** This is at-least-once + idempotent consumer = exactly-once *in effect* — the
only exactly-once that exists across a broker. One real hole remains: a crash after SMTP accepted the
message but before the transaction commits rolls back the claim, and the redelivery sends a second
email. It cannot be closed without a distributed transaction. Against a provider that supports it you
would pass `eventId` as the provider's own idempotency key (the SES/SendGrid message id) and let the
provider collapse the duplicate; local SMTP has no such key, so it stays **documented rather than
claimed as solved**. The source javadoc says exactly this.

Contrast worth drawing: Booking's `MovieProjector` is idempotent *for free* — its PK is the assigned
movie id, so a redelivery just rewrites the same row. Notification's side effect — an email — is
**not naturally idempotent**, which is precisely why it needs a dedupe table.

## The egress (middle and bottom rows)

After the claim, two in-process calls fan out, each to one external system:

- **`KeycloakUserClient.emailOf(sub)`** → **Keycloak** (blue, `LOOKUP EMAIL`). Resolves the
  recipient address via the Admin API (`GET /admin/realms/cinema/users/{sub}`), authenticating with a
  **machine token** (client-credentials, `MachineTokenProvider`, least-privilege `view-users` role),
  Caffeine-cached (`user-emails`, 10m TTL, bounded 10k).
- **`EmailChannel.send(...)`** → **SMTP** (blue, `SMTP`). Renders a Thymeleaf template
  (`templates/email/booking-confirmed.html` / `booking-rejected.html`) and sends
  `multipart/alternative` (plain text + HTML) over SMTP. The provider is a config change only:
  Mailpit locally, SES/SendGrid via env vars.

The dead-letter path drops down the left edge: when a message exhausts its five retries
(`default-requeue-rejected: false`), the queue's dead-letter exchange routes it to
`notification-booking-events-dlq` — **inspectable**, because a ticket that cannot be sent is a
customer problem, not a log line.

Retryable vs terminal errors is the distinction the DLQ exists to serve: `NOTIFICATION_IDENTITY_UNAVAILABLE`
(Keycloak down) retries; `NOTIFICATION_RECIPIENT_UNKNOWN` (user has no email) never succeeds on retry
and dead-letters. Read `exception/NotificationErrorCode.java` for the full list.

## The data zone

One table in `notification-db`: `processed_events`, Liquibase-migrated with `ddl-auto=validate`. The
label worth reading is **event_id = ASSIGNED PK** — the whole dedupe story in three words.

Also worth a mention, folded into node sublabels: **the event is a document.** Seats, showtime,
theater, screen, movie title and poster *path* all ride on the event because Booking snapshotted them
— so this service never calls Booking or Catalog back. The email address is the single exception,
because it must be *current* rather than historical. The poster is composed as
`{baseUrl}/{posterSize}{path}` at render time (`NotificationProps.Image`), so changing CDN or image
size does not require re-syncing stored data; a null poster is a real case and the template drops the
image band.

## Icons

| Icon | Where | Style | Source |
|---|---|---|---|
| ticket | Booking node | stroked | **custom** |
| inbox | BookingEventListener | stroked | **custom** |
| shield-check | NotificationService | stroked | **custom** |
| key | KeycloakUserClient, Keycloak | stroked | **custom** |
| mail · send | EmailChannel, SMTP | stroked | **custom** |
| alert | DLQ | stroked | **custom** |
| PostgreSQL | `processed_events`, legend | filled | Simple Icons (CC0) |
| RabbitMQ | RabbitMQ node, legend | filled | Simple Icons (CC0) |

The seven domain icons are **not** from the skill's icon library — that set is IT/cloud only (server,
database, queue, k8s, …) and has no cinema, mail, or identity glyphs. They were drawn to match its
house style exactly: 24×24 viewBox, `fill="none"`, `stroke="currentColor"`, `stroke-width="1.5"`,
round caps and joins — the same Tabler idiom as the stock `database` glyph, so they sit beside it
without looking foreign. All are `<symbol>` definitions in `<defs>`, placed with `<use>` and coloured
via `color=` (they inherit through `currentColor`); to recolour one, change the `color` attribute on
its `<use>`.

Why these shapes: the **ticket** is the thing Booking ships; the **inbox** is the consume-only
ingress; the **shield-check** is an invariant defended (the dedupe) — coral on the focal node; the
**key** is identity, used for both the client and the IdP; **mail** vs **send** distinguishes the
message from the transport; the **alert** is the dead-letter destination for messages that can never
be delivered.

## Deliberately out of scope

The gateway and Eureka `lb://` registration; the `@ConfigurationProperties` / Liquibase / caching
plumbing (folded into node sublabels); the step-by-step SQL of the claim (it is one native `INSERT` —
see the `claim` query in `ProcessedEventRepository`); Payment and the rest of the system, which never
touch this service.

## Regenerating

The `.svg` is extracted from the HTML (first `<svg>` node, with a Google Fonts `@import` injected and
`&` XML-escaped). A `.png` was **not** generated — this checkout has no committed rasterizer, so the
`.html` and `.svg` are the canonical assets and render correctly in any modern browser as-is. To add a
PNG: `pip install playwright && playwright install chromium`, then screenshot the `<svg>` node.