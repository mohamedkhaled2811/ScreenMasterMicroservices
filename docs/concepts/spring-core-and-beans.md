# Spring Core: IoC, beans, and dependency injection

## What it is
Spring is, at its heart, a **container that creates and wires your objects for you**. You don't write `new PaymentService(new PaypalClient(...))`; you declare what you need and Spring hands you a fully-built object. Those container-managed objects are called **beans**.

## Why it exists
Wiring objects by hand (`new ...` everywhere) couples classes to *concrete* implementations and to the order of construction. **Inversion of Control (IoC)** flips that: the framework owns object creation and lifecycle, so your code depends on *interfaces*, stays testable (swap a real `PaymentClient` for a fake in tests), and you stop writing boilerplate factory code.

**Dependency Injection (DI)** is how IoC is delivered: the container *injects* a bean's collaborators into it.

## The core annotations

| Annotation | Means |
|---|---|
| `@Component` | "Spring, manage an instance of this class as a bean." Generic stereotype. |
| `@Service` | A `@Component` that holds business logic. Same behaviour, clearer intent. |
| `@Repository` | A `@Component` for data access; also translates DB exceptions. |
| `@Controller` / `@RestController` | A `@Component` that handles web requests (see [spring-web-annotations.md](spring-web-annotations.md)). |
| `@Configuration` | A class that *defines* beans via `@Bean` methods. |
| `@Bean` | On a method inside `@Configuration`: "the object this method returns is a bean." Used when you can't annotate the class (e.g. a library class). |
| `@Autowired` | "Inject the matching bean here." Optional on constructors since Spring 4.3. |

## Example — constructor injection (the style we use)
```java
@Service
public class BookingService {

    private final SeatRepository seatRepository;
    private final PaymentClient paymentClient;

    // No @Autowired needed: one constructor → Spring injects automatically.
    public BookingService(SeatRepository seatRepository, PaymentClient paymentClient) {
        this.seatRepository = seatRepository;
        this.paymentClient  = paymentClient;
    }
}
```

A `@Bean` method, for wiring a class you don't own:
```java
@Configuration
public class WebClientConfig {

    @Bean
    public RestClient catalogRestClient() {
        return RestClient.builder()
                .baseUrl("http://catalog:8081")
                .build();
    }
}
```
Now any bean can ask for a `RestClient` in its constructor and get this one.

## How we use it here
The monolith already uses this everywhere (`@Service` classes, `@Configuration` for `SecurityConfig`/`RabbitMQConfig`/`WebClientConfig`). When we split into microservices, **each service is its own Spring container** with its own beans — `BookingService` lives only in the booking service, `PaymentService` only in payment.

## Gotchas / interview lens
- **Prefer constructor injection** over field injection (`@Autowired` on a field). Constructor injection makes dependencies explicit, allows `final` fields, and works without Spring in unit tests. Lombok's `@RequiredArgsConstructor` generates the constructor for you (see [lombok.md](lombok.md)).
- **Beans are singletons by default** — one shared instance per container. That's why they must be stateless (no per-request fields).
- "What is IoC?" → "The framework, not my code, controls object creation and lifecycle; DI is how it injects collaborators." That one sentence answers the question.