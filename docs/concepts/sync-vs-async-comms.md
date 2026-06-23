# Communication: sync vs async, REST vs gRPC, command vs event

## The first fork — answer this before naming a protocol
**Does the caller need the answer *now* to proceed?**

- **Synchronous (request/response — REST, gRPC):** the caller blocks until it gets the result. Booking → Payment is sync — you can't confirm a booking without knowing whether the charge succeeded. Costs: *temporal coupling* (both must be up), latency adds along chains, failures cascade (hence all of [resilience-patterns.md](resilience-patterns.md)).
- **Asynchronous (messages/events — RabbitMQ, Kafka):** the caller fires and moves on. Booking → Notification is async — tickets can be emailed seconds later. Buys: decoupling in time, natural buffering under load spikes, one event fans out to many consumers. Costs: eventual consistency, harder debugging, a broker to operate.

**The senior heuristic:** *default to async between services; go sync only where the user's request genuinely cannot complete without the answer.* Deep synchronous chains (A→B→C→D) are the smell — availability multiplies down: four 99.9% services chained ≈ 99.6%.

## REST vs gRPC (within sync)

| | REST + JSON | gRPC + Protobuf |
|---|---|---|
| contract | OpenAPI (optional, drifts) | `.proto` file *is* the source of truth; code generated |
| wire | text JSON over HTTP/1.1 — readable, `curl`-able | binary over HTTP/2 — smaller, faster, multiplexed |
| streaming | awkward (SSE/WebSocket bolt-ons) | first-class (server/client/bidirectional) |
| deadlines | roll your own | built into the protocol |
| reach | every client, browsers, CDNs | browsers need grpc-web; mostly internal |
| pick for | public APIs, simple CRUD, max interop | high-QPS internal calls, low latency, streaming |

```proto
// payment.proto — the contract IS this file
syntax = "proto3";
service PaymentService { rpc Charge (ChargeRequest) returns (ChargeResult); }
message ChargeRequest {
  int64  booking_id   = 1;   // FIELD NUMBERS go on the wire, not names —
  string currency     = 2;   // so renaming is safe, renumbering breaks compatibility.
  int64  amount_minor = 3;   // add fields, never reuse numbers.
}
message ChargeResult { bool approved = 1; string provider_ref = 2; }
```

**This lab uses REST**, not gRPC — the `.proto` file above plus this table is interview-sufficient (field guide scope guard). We keep gRPC as a name-drop.

## Evolving APIs without breaking consumers (both REST and gRPC)
Additive changes only (new *optional* fields); never repurpose a field; version when you must (`/v2` path for REST, new proto messages for gRPC). Ideally verify with **consumer-driven contract tests** (Pact) in CI — name-dropping that answers the follow-up before it's asked.

## Command vs event — be precise
- A **command** ("ChargePayment") is addressed to *one* handler and expects action. Orchestrated sagas send commands.
- An **event** ("BookingConfirmed") states a *fact*; the sender doesn't know or care who listens. Choreographed sagas run on events.

## How we use it here
- **Sync REST:** Booking → Payment (must know the charge result); Booking → Catalog for the API-composition query path.
- **Async events (RabbitMQ):** Booking → Notification via `BookingConfirmed` (through the [outbox](transactional-outbox.md)); Catalog → Booking `MovieUpdated` for the CQRS read model.

## Interview lens
"First fork: does the caller need the answer to proceed? No → async messaging; yes → sync. Within sync, REST for public/interoperable APIs, gRPC for high-throughput internal calls with codegen and streaming. My default is async between services wherever the UX allows, sync only on the critical path (booking → payment)."
