# Spring Web: REST controllers and HTTP clients

## What it is
The annotations that turn a class into an HTTP API (`@RestController` and friends) and the clients a service uses to call *other* services' HTTP APIs (`RestClient`, `WebClient`).

## Why it exists
In microservices, REST over HTTP is the default synchronous wire format between services and the format the gateway exposes to browsers/mobile. You need both halves: serve requests, and make requests.

## Serving requests

```java
@RestController
@RequestMapping("/bookings")          // common prefix for every method here
public class BookingController {

    private final BookingService bookingService;
    public BookingController(BookingService bookingService) { this.bookingService = bookingService; }

    @PostMapping                       // POST /bookings
    public ResponseEntity<BookingResponse> create(@RequestBody BookingRequest req) {
        BookingResponse body = bookingService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/reference/{reference}")   // GET /bookings/reference/BK-1A2B3C4D
    public BookingResponse byReference(@PathVariable String reference) {
        return bookingService.findByReference(reference);
    }

    @PatchMapping("/{id}/status")
    public BookingResponse updateStatus(@PathVariable int id,
                                        @RequestParam BookingStatus status) {
        return bookingService.updateStatus(id, status);
    }
}
```

| Annotation | Role |
|---|---|
| `@RestController` | `@Controller` + `@ResponseBody`: return values are serialized to JSON, not view names. |
| `@RequestMapping("/bookings")` | Base path (and can set method/headers). |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` / `@DeleteMapping` | Method-specific shortcuts. |
| `@RequestBody` | Deserialize the JSON request body into this parameter. |
| `@PathVariable` | Bind a `{placeholder}` from the URL path. |
| `@RequestParam` | Bind a `?query=param`. |
| `ResponseEntity<T>` | Full control of status code + headers + body. Return it when you need a non-200 status. |

**DTOs, not entities, cross the wire.** The monolith has `dto/request` and `dto/response` packages for exactly this reason: the JSON contract is decoupled from the JPA entity, so internal schema changes don't break clients. This matters *more* across services, where the DTO is a published contract.

## Calling other services

Spring Boot 3.2+ adds `RestClient` (synchronous, fluent). The monolith uses `WebClient` (reactive, from WebFlux). Both work; `RestClient` is simpler for plain request/response.

```java
@Service
public class CatalogClient {
    private final RestClient rest;                 // injected from a @Bean (see WebClientConfig pattern)

    public CatalogClient(RestClient catalogRestClient) { this.rest = catalogRestClient; }

    public MovieDto getMovie(int movieId) {
        return rest.get()
                   .uri("/api/movies/{id}", movieId)
                   .retrieve()
                   .body(MovieDto.class);
    }
}
```

## How we use it here
- Each service exposes its own REST controllers; the **gateway** routes external paths to them (see [api-gateway-and-bff.md](api-gateway-and-bff.md)).
- Booking → Payment and Booking → Catalog (the API-composition path) are synchronous `RestClient` calls.
- The TMDB integration in Catalog uses `WebClient` to call an external API.

## Gotchas / interview lens
- **Internal calls don't go back out through the gateway** — service → service is direct (via discovery), not via the public edge.
- Every outgoing call needs a **timeout** (see [resilience-patterns.md](resilience-patterns.md)); a `RestClient`/`WebClient` with no timeout is how one slow dependency takes you down.
- API evolution: add optional fields, never repurpose or remove them; version (`/v2`) when you must. Same rule for REST and gRPC.
