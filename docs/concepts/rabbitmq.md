# RabbitMQ

## What it is
RabbitMQ is a **message broker**: producers publish messages, the broker routes them into queues, and consumers pull from queues. It's a *smart broker* — routing logic lives in the broker (exchanges + bindings), and once a consumer acks a message it's gone.

## Why it exists
It's the async backbone (see [sync-vs-async-comms.md](sync-vs-async-comms.md)): it decouples sender from receiver in time, buffers bursts, lets one event fan out to many consumers, and survives a consumer being temporarily down (messages wait in the queue).

## The model — four words

```
producer ──publish(routing key)──▶ [ EXCHANGE ] ──binding──▶ [ QUEUE ] ──▶ consumer
```

- **Exchange** — receives messages and decides where they go. ScreenMaster uses a **topic exchange** (`screenmaster-exchange`): it routes by matching the message's *routing key* against each binding's pattern.
- **Routing key** — a string on the message (`email-verification-key`, `booking-key`) the exchange matches against bindings.
- **Queue** — where matched messages wait for a consumer (`vqueue`, `bqueue`).
- **Binding** — the rule connecting an exchange to a queue for a routing-key pattern.

**Competing consumers:** put N consumers on one queue and the broker round-robins messages across them — that's how you scale a worker pool. **Acks:** a message is redelivered if the consumer dies before acking — which is exactly why consumers must be [idempotent](idempotent-consumer.md).

## ScreenMaster's existing flows (from the schema doc)
| Queue | Routing key | Flow |
|---|---|---|
| `vqueue` | `email-verification-key` | **active**: register → producer sends `VerificationEmailDto` → consumer sends SMTP mail via Thymeleaf template |
| `bqueue` | `booking-key` | **declared but unwired** — reserved seam for booking-event notifications |

That unused `bqueue` is the ready seam for the [outbox](transactional-outbox.md) → Notification flow (field guide M4).

## Spring AMQP example
```java
// config: exchange, queue, binding
@Bean TopicExchange exchange() { return new TopicExchange("screenmaster-exchange"); }
@Bean Queue bookingQueue()     { return new Queue("bqueue"); }
@Bean Binding bookingBinding(Queue bookingQueue, TopicExchange exchange) {
    return BindingBuilder.bind(bookingQueue).to(exchange).with("booking-key");
}

// producer
rabbitTemplate.convertAndSend("screenmaster-exchange", "booking-key", bookingConfirmedDto);

// consumer
@RabbitListener(queues = "bqueue")
public void onBookingConfirmed(BookingConfirmedDto evt) {
    // dedupe by evt.eventId(), then "send" the email
}
```
A `Jackson2JsonMessageConverter` serializes DTOs to JSON on the wire (already configured in the monolith).

## RabbitMQ vs Kafka (one breath)
- **RabbitMQ** = smart broker: exchanges route into queues, competing consumers, **ack-and-gone**. Best for *task distribution and rich routing*. (This project's email + booking flows.)
- **Kafka** = distributed append-only **log**: events are *retained* in partitioned topics; consumer groups track their own offsets, so many independent consumers read the same stream and can **replay** history (rebuild a read model), with ordering per partition. Best for *event streaming, replay, high-throughput fan-out*.

Heuristic: **work queues + routing → RabbitMQ; streaming + replay + fan-out → Kafka.** We use RabbitMQ (already in the stack); Kafka stays a comparison.

## How we use it here
M4's outbox relay publishes `BookingConfirmed` to `bqueue`; Notification consumes idempotently. We also propagate the **trace id** into message headers so async hops show up in one trace (see [observability.md](observability.md)).

## Interview lens
"RabbitMQ is a smart broker — exchanges route into queues, competing consumers, ack-and-gone; great for task queues and routing. Kafka is a log — retained partitioned topics, consumer-group offsets, replay, ordering per partition; great for streaming and fan-out. I used RabbitMQ in my cinema project for email jobs."
