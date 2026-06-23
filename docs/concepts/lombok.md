# Lombok: kill the boilerplate

## What it is
Lombok is an annotation processor that **generates getters, setters, constructors, `equals`/`hashCode`, builders, and loggers at compile time**, so you don't type them. It's already a dependency in this project's `pom.xml`.

## Why it exists
A Java class with 8 fields needs ~80 lines of mechanical getter/setter/constructor code that adds no information. Lombok replaces it with a one-line annotation, keeping classes readable.

## The annotations you'll actually use

| Annotation | Generates |
|---|---|
| `@Getter` / `@Setter` | Getters/setters for all fields (or one field if placed on a field). |
| `@ToString` | A `toString()` of all fields. |
| `@EqualsAndHashCode` | `equals()` + `hashCode()`. |
| `@NoArgsConstructor` | Empty constructor (JPA needs this on entities). |
| `@AllArgsConstructor` | Constructor with every field. |
| `@RequiredArgsConstructor` | Constructor for `final` (and `@NonNull`) fields — **the DI workhorse**. |
| `@Data` | `@Getter`+`@Setter`+`@ToString`+`@EqualsAndHashCode`+`@RequiredArgsConstructor` in one. Great for DTOs. |
| `@Builder` | A fluent builder: `Booking.builder().reference("BK-1").status(PENDING).build()`. |
| `@Slf4j` | A `private static final Logger log` field — `log.info(...)`. |

## Example — constructor injection without the constructor
```java
@Service
@RequiredArgsConstructor                 // generates the constructor for the final fields
@Slf4j                                    // gives you `log`
public class BookingService {

    private final SeatRepository seatRepository;     // ← injected via generated constructor
    private final PaymentClient paymentClient;

    public BookingResponse create(BookingRequest req) {
        log.info("creating booking for showtime {}", req.showtimeId());
        ...
    }
}
```
This is the idiom we use everywhere: `@RequiredArgsConstructor` + `final` fields = clean constructor injection (see [spring-core-and-beans.md](spring-core-and-beans.md)).

A DTO with a builder:
```java
@Data
@Builder
public class BookingResponse {
    private int id;
    private String reference;
    private BookingStatus status;
    private BigDecimal total;
}
```

## How we use it here
- `@RequiredArgsConstructor` for every `@Service`/`@RestController` to inject dependencies.
- `@Slf4j` for structured logging (replacing the monolith's `System.out.println` debugging — a flagged gap).
- `@Builder` + `@Data` for request/response DTOs.

## Gotchas / interview lens
- **`@Data` on JPA entities is risky**: its generated `equals`/`hashCode`/`toString` touch *all* fields, including lazy associations — triggering loads or `LazyInitializationException`, and breaking entity identity semantics. On entities, prefer `@Getter`/`@Setter` and hand-write or scope `equals`/`hashCode` to the id.
- Lombok runs at **compile time** — your IDE needs the Lombok plugin to "see" the generated methods, but the build doesn't depend on the IDE.
- It's excluded from the final fat jar in the build config (it's compile-only), which is correct — the generated code is already baked in.
