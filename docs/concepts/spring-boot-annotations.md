# Spring Boot: starters, auto-configuration, and config

## What it is
Spring Boot is Spring plus **opinionated defaults**: it auto-configures beans based on what's on the classpath, bundles dependencies into **starters**, and embeds the web server so each service runs as a plain `java -jar`. That last point is exactly what microservices need — every service is a self-contained runnable.

## Why it exists
Classic Spring made you configure everything by hand (XML, server setup, plumbing). Boot's bet: *most apps want the same plumbing*, so ship it pre-wired and let you override only what's different.

## The key pieces

### `@SpringBootApplication`
The one annotation on your `main` class. It's three annotations in one:
- `@Configuration` — this class can define beans.
- `@EnableAutoConfiguration` — turn on Boot's "look at the classpath and configure sensible beans" magic.
- `@ComponentScan` — scan this package and below for `@Component`/`@Service`/etc.

```java
@SpringBootApplication
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
```
**Component scan scope = the package of this class and its sub-packages.** Put your code under that package or Spring won't find your beans.

### Starters
A starter is a curated dependency bundle. Add one line, get everything wired:

| Starter | Brings |
|---|---|
| `spring-boot-starter-web` (webmvc) | Tomcat + Spring MVC for REST controllers |
| `spring-boot-starter-data-jpa` | Hibernate + Spring Data repositories |
| `spring-boot-starter-security` | Spring Security filter chain |
| `spring-boot-starter-amqp` | RabbitMQ client + `RabbitTemplate` |
| `spring-boot-starter-actuator` | health/metrics endpoints (used in observability) |
| `spring-cloud-starter-gateway-server-webmvc` | the API gateway |
| `spring-cloud-starter-netflix-eureka-client` | register with Eureka discovery |

(These are exactly the deps already in this project's `pom.xml`.)

### Auto-configuration
Because `spring-boot-starter-data-jpa` and `postgresql` are on the classpath, Boot auto-creates a `DataSource`, an `EntityManagerFactory`, and a `JpaTransactionManager` — you wrote none of it. Auto-config *backs off* the moment you define your own bean of that type.

### Externalized configuration
Behaviour comes from config, not code, so the **same jar runs in every environment**:
- `application.properties` / `application.yml` — key/value config.
- Environment variables and `--args` override file values (12-factor).
- `@ConfigurationProperties` binds a group of keys to a typed object:

```java
@ConfigurationProperties(prefix = "payment")
public record PaymentProps(String baseUrl, double failRate) {}
// reads payment.base-url and payment.fail-rate
```

- **Profiles** (`@Profile("dev")`, `spring.profiles.active=docker`) swap beans/config per environment.

## How we use it here
- Every microservice = one `@SpringBootApplication` main class, one `application.yml`, one runnable jar.
- The fake `payment` service will read `FAIL_RATE` from config to simulate failures (field guide M1/M3).
- Secrets (JWT key, DB password) come from **env vars**, never hard-coded — fixing the monolith's "hard-coded JWT secret" gap.

## Gotchas / interview lens
- "How does auto-configuration know what to configure?" → conditional beans (`@ConditionalOnClass`, `@ConditionalOnMissingBean`) keyed off the classpath; your own beans win.
- One jar, many environments — config is injected, not baked. This is the heart of [12-factor](https://12factor.net) and shows up again in [kubernetes-vocabulary.md](kubernetes-vocabulary.md) (ConfigMaps/Secrets).
