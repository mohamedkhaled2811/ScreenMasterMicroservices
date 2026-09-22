# Notification channels — turning an event into a real side effect

**Where it lives:** `notification/src/main/java/com/gr74/notification/channel/`
**Milestone:** Phase 4.3 follow-on — the point where "sending the email" stops being a `log.info`.
**Related:** [idempotent-consumer.md](idempotent-consumer.md), [html-email-rendering.md](html-email-rendering.md), [transactional-outbox.md](transactional-outbox.md), [cqrs-read-model.md](cqrs-read-model.md)

---

## The one-sentence version

`NotificationService` decides **what to say**; a `NotificationChannel` **delivers it** — and the seam
between those two is what makes the idempotency testable without a mail server.

---

## 1. The three pieces

```
NotificationChannel   interface — send(Notification), throws on failure
Notification          record(recipient, subject, templateName, variables)
EmailChannel          the only implementation: JavaMailSender + Thymeleaf -> MimeMessage
```

`NotificationService` builds a `Notification` from the event and hands it over. It never touches
`JavaMailSender`, never renders HTML, and never learns what SMTP is.

## 2. "An interface with one implementation" — why this one is allowed

Normally that is exactly the abstraction to delete, and this repo's default is to delete it. Two
things earn it here:

1. **A second channel is a stated requirement** (SMS). When it arrives it is one new class; the
   service and the listener do not change.
2. **It is the test seam.** The central claim of Phase 4 is *"two deliveries, one email"*. With
   `JavaMailSender` inlined into the service, proving that needs a running mail server. With this
   interface, it needs a three-line recording stub — see `NotificationServiceTest.RecordingChannel`.

If SMS never materialises, collapse it: put `JavaMailSender` in the service and delete the package.
That is a real option, not a face-saving footnote.

## 3. Why the body is a template name + variables, not a rendered String

If the service rendered the HTML, the service would own **email markup** — and an SMS channel would
be handed HTML and have to strip it. Naming the template and passing the data keeps rendering a
*channel* concern:

| Channel | `"booking-confirmed"` resolves to |
|---|---|
| `EmailChannel` | `templates/email/booking-confirmed.html`, rendered by Thymeleaf |
| a future `SmsChannel` | a 160-character text template, from the **same** variable map |

## 4. The failure contract: **throw, never swallow**

A channel must not catch and log a delivery failure. The call happens inside the transaction holding
the `processed_events` claim, so:

```
claim (INSERT)  →  resolve recipient  →  send  →  commit
                                         ↑
                            throws  →  transaction rolls back
                                    →  the CLAIM rolls back with it
                                    →  message is nacked
                                    →  RabbitMQ redelivers
                                    →  the retry re-claims cleanly and tries again
```

A channel that caught the exception would commit a claim for an email that never went out — marking a
customer's lost ticket as successfully processed, with every dashboard green. That is why
`NotificationSendException` is documented as *meant to escape*, and why
`NotificationServiceTest.sendFailurePropagates` exists.

Bounded by `spring.rabbitmq.listener.simple.retry` (5 attempts, exponential backoff to 30s) and then
a **dead-letter queue** — see §6.

## 5. The recipient: the one fact not snapshotted

Everything on the ticket — seats, showtime, theater, screen, movie title, poster — is snapshotted onto
`BookingConfirmed` by Booking, so this consumer has **zero synchronous dependencies** on Booking or
Catalog. The email address is the deliberate exception:

- A ticket is a **historical record** — it must say what was true at confirm time.
- An address is the opposite — it must be **current**. Snapshotting it would mail a changed address
  to the old one.

So `KeycloakUserClient.emailOf(sub)` calls Keycloak's Admin API, authenticating with
`MachineTokenProvider` (client credentials, the least-privilege `view-users` role on the
`notification-svc` client). This is the **first caller** of that provider, whose javadoc had said
*"nothing calls this provider yet — that is intentional."*

**Why a machine token and not the user's:** this runs on a consumer thread seconds after the user's
request died. There is no user token to relay, and putting one in a queue message would be a
credential at rest that is expired by consume time anyway. Contrast Payment's
`UserTokenRelayInterceptor`, which *does* relay — because there, a user is waiting.

### The coupling, stated honestly

This re-introduces a synchronous dependency on the consume path, which is what Phase 4 exists to
remove. It is accepted because **there is no degraded mode**: you cannot email someone without their
address, and a ticket to a fallback address is worse than a late one. Failing and retrying is the
correct behaviour, not a compromise. It is made rare by a Caffeine cache (10-minute TTL, see
`CacheConfig`) — expire-**after-write**, so a busy user cannot refresh their own entry forever and
never pick up a changed address.

## 6. Retry and the dead-letter queue

Not every failure is retryable, and treating them alike gives you either a hot loop or a lost ticket:

| Failure | Code | Retryable? |
|---|---|---|
| SMTP down, Mailpit restarting | `NOTIFICATION_SEND_FAILED` | yes — it will fix itself |
| Keycloak unreachable | `NOTIFICATION_IDENTITY_UNAVAILABLE` | yes |
| User has **no email on file** | `NOTIFICATION_RECIPIENT_UNKNOWN` | **no** — redelivery cannot conjure an address |

After `max-attempts`, `default-requeue-rejected: false` means the message is **rejected**, and because
the queue is declared with a dead-letter exchange (`RabbitConfig.NOTIFICATION_DLX`), it lands in
`notification-booking-events-dlq` with its payload intact. A ticket that could not be sent is a
customer who paid and got nothing — it must be inspectable, not a log line that rolled over. Browse it
at <http://localhost:15672>.

> ⚠️ **Adding the dead-letter arguments changes the queue declaration.** RabbitMQ rejects a
> redeclaration with different arguments (`PRECONDITION_FAILED`), so an environment that already has
> the old queue must delete it once. That is the cost of topology-in-code.

## 7. The honest residual

This is at-least-once delivery with an idempotent consumer — exactly-once *in effect*, the only
exactly-once that exists across a broker. One gap remains, and it became real when the side effect
left the process:

> A crash **after** SMTP accepted the message but **before** the transaction commits rolls back the
> claim, and the redelivery sends a second email.

It cannot be closed without a distributed transaction. Against a provider that supports it, you would
pass `eventId` as the provider's own **idempotency key** (the SES/SendGrid message id) and let the
provider collapse the duplicate. Local SMTP has no such key, so this stays documented rather than
claimed as solved.

## 8. A Boot-4 trap: a transitive dependency auto-configures nothing

The first container boot after this change failed with:

```
No qualifying bean of type 'org.springframework.web.client.RestClient$Builder' available
```

…while the whole unit suite was green. Cause: `spring-boot-starter-restclient` was declared at
**test** scope (it had been added only so `TestRestTemplate` could introspect `RestTemplateBuilder`
in the actuator-health test). `RestClient` was still on the main classpath *transitively*, so the
code **compiled** — but Boot 4 auto-configures from the modules you actually declare, so no builder
bean was ever created.

Two lessons, both general:

- **Compiling is not the same as being auto-configured.** Boot 4 split `RestClient` out of the web
  starter; if you use it in main sources, declare the starter in main scope. The pom comment had even
  predicted this moment — *"Part 2's Keycloak Admin API lookup will add the real client then"*.
- **A `@DataJpaTest` slice will not catch it.** Only a full-context test (or a container boot) builds
  every bean. `NotificationApplicationTests` exists for exactly this and passed only because the test
  classpath *did* have the starter — the scope difference is invisible to it.

The client is now built explicitly in `KeycloakClientConfig`, **with timeouts** (2s connect / 2s
read), and deliberately **not** published as a `RestClient.Builder` bean — Eureka's transport
autowires a builder *by type*, the trap `BookingClientConfig` and `CatalogClientConfig` both document
at length. Timeouts are load-bearing here rather than decorative: this call runs on a listener thread
holding an open transaction and the idempotency claim, so an unbounded read would pin that thread
(and eventually every listener thread) on one unresponsive Keycloak.

## 9. Dev transport: Mailpit

`compose.yaml` runs [Mailpit](https://mailpit.axllent.org/) — a fake SMTP server (port 1025) with a
web inbox (**<http://localhost:8025>**). Real SMTP, real `JavaMailSender`, nothing mocked; only the
far end is local. You can *look at the rendered ticket*, which is the only practical way to iterate
on HTML email.

Pointing at SES/SendGrid later is **four config properties and zero code** — that is the entire reason
delivery goes through `JavaMailSender` rather than a provider SDK.

*(Mailpit is the maintained successor to the archived MailHog.)*

---

## Interview answers this gives you

- *"How do you make a non-idempotent side effect safe under at-least-once delivery?"* — claim-first
  in the same transaction as the send; the claim rolls back with a failed send, so the retry is clean.
- *"Where do messages go when the consumer can never succeed?"* — a DLQ, reached by distinguishing
  retryable from non-retryable failures with coded exceptions.
- *"When is it OK to add a synchronous call to an async consumer?"* — when there is no degraded mode
  and failing is the correct behaviour; then make it rare with a cache and bounded with a DLQ.
