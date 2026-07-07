# OpenAPI & springdoc

**Why this, here:** the API contract for a frontend was being **hand-authored** as wire-level Markdown in
[`docs/frontend/`](../frontend/) — every endpoint, request/response shape, the `PagedModel` envelope, the
sort whitelists, and each service's error-code table transcribed by hand from the controllers. That drifts
the moment the code changes. **springdoc** introspects the controllers, record DTOs, and Bean-Validation
annotations at runtime and serves an always-in-sync **OpenAPI 3.1** document + **Swagger UI** instead.
`payment/` is the reference; `catalog/` and `booking/` follow the same shape; the **gateway** hosts one
aggregated UI.

## 1. What it is

**OpenAPI** is a JSON/YAML standard that describes a REST API — its paths, verbs, parameters, request/response
schemas, and error shapes — in a machine-readable document. **Swagger UI** renders that document as a browsable,
"Try it out" web page. **springdoc-openapi** is the Spring library that *generates* the OpenAPI document from a
running Spring MVC app (no hand-writing the spec) and bundles Swagger UI to view it.

## 2. Why it exists

A REST API's contract otherwise lives in three drifting places at once: the code, whatever docs someone wrote,
and the caller's assumptions. springdoc collapses that to one source of truth — the **code** — because it reads
the actual `@*Mapping` methods, `record` DTOs, and `@NotNull`/`@Size`/`@DecimalMin` constraints at startup and
emits a spec that matches what the app really does. A frontend dev then reads one page (or feeds the spec to a
client-generator) instead of reading Java or guessing. The field guide frames OpenAPI as REST's contract
mechanism with the caveat that it "drifts" (vs gRPC's `.proto`) — springdoc is the answer to *why it drifts less*
when it's generated rather than written.

## 3. Example (tied to ScreenMaster)

One dependency (version managed in the parent pom) turns a controller into a documented API:

```xml
<!-- each web service's pom.xml -->
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

springdoc infers most of the spec on its own. We only add what it *can't* infer — document metadata and the
public server URL — in a tiny `config/OpenApiConfig`:

```java
@Configuration
public class OpenApiConfig {
    @Value("${openapi.public-url:http://localhost:8080}")
    private String publicUrl;

    @Bean
    OpenAPI paymentOpenApi() {
        return new OpenAPI()
            .info(new Info().title("ScreenMaster — Payment API").version("v1")…)
            // advertise the GATEWAY path, not the bare service path — see §5.
            .servers(List.of(new Server().url(publicUrl + "/api")));
    }
}
```

Per-endpoint, `@Operation` / `@ApiResponse` describe the happy path and the error `code`s that endpoint can
return. The spec then serves at `/v3/api-docs` and the UI at `/swagger-ui.html`.

## 4. How we use it here

- **Every web service** (`catalog` 8081, `booking` 8082, `payment` 8083) gets the starter, a
  `config/OpenApiConfig` (title/version/description + a gateway `Server` URL), `application.yml` springdoc props
  (`api-docs.version=openapi_3_1`), and `@Tag`/`@Operation`/`@ApiResponse` annotations on its controllers.
  `notification`/`discovery` have no controllers, so nothing to document.
- **The gateway** hosts the **one aggregated Swagger UI** at `http://localhost:8080/swagger-ui.html`. Its own
  spec generation is disabled (`springdoc.api-docs.enabled=false` — it owns no endpoints); instead
  `springdoc.swagger-ui.urls` lists each backend's spec, proxied through docs-only routes
  (`/<service>/v3/api-docs` → `lb://<service>` with `StripPrefix=1`) so the browser fetches them **same-origin**
  (no CORS). A frontend dev opens one URL and picks catalog / booking / payment from a dropdown.
- **The custom error `code`** on our RFC 9457 `ProblemDetail` (see
  [error-handling-problemdetail.md](error-handling-problemdetail.md)) is documented explicitly with a small
  `exception/ApiError` schema mirror, because springdoc can't infer it (§5).
- **Paged listings** (`Page<DTO>`, serialized `VIA_DTO` per
  [pagination-and-filtering.md](pagination-and-filtering.md)) render as the real `PagedModel` envelope
  (`{ content, page }`) in the spec — not the internal `PageImpl` — with no extra work.
- **Filter + `Pageable` params are flattened** with springdoc's `@ParameterObject`, so `GET /movies` shows
  `title`, `minRating`, `page`, `size`, `sort`, … as individual query params instead of two opaque objects.

## 5. Gotchas / interview lens

- **The `/api` prefix / edge reconciliation (the microservices bit).** Each service only knows its **bare**
  path (`/movies`); clients call it through the gateway at `/api/movies` (the gateway strips `/api` via
  `StripPrefix=1`). So the path in a service's own spec is *not* the path the frontend must call. We fix it by
  advertising the gateway base + `/api` as the OpenAPI `Server`, so operation paths stay `/movies` while the
  server prefix supplies `/api` and "Try it out" hits the real public URL. This is the edge/BFF cost made
  concrete — one client-facing contract stitched from services that each know only their own slice (see
  [api-gateway-and-bff.md](api-gateway-and-bff.md)).
- **The custom `code` property is invisible to springdoc.** Our error body is a Spring `ProblemDetail` with
  `setProperty("code", errorCode.name())` added at runtime; springdoc only sees the framework's `ProblemDetail`
  type, which has no such field. We document it with a hand-written `ApiError` schema mirror per service
  (`allowableValues` = the `<Service>ErrorCode` names). **Coupling is manual** — if you add or rename an error
  code, update the mirror too, or the docs drift from the enum. This *is* the "OpenAPI drifts" caveat, contained.
- **Version compatibility is a live constraint.** Spring Boot 4 needs the springdoc **v3** line
  (`3.0.3`, built on `spring-boot-starter-parent:4.0.5`); the older `2.8.x` line is Spring Boot 3 only. Pin it in
  the parent pom and verify the UI actually renders after a Spring Boot upgrade — springdoc tracks Spring Boot
  closely but lags a new major by a bit.
- **`enabled` in prod.** springdoc warns that `/v3/api-docs` and `/swagger-ui.html` are on by default; a real
  deployment gates them (`springdoc.api-docs.enabled=false`) or puts them behind auth. For this lab they stay open.
- **DTOs, not entities, in the spec.** Because controllers return DTO `record`s (project convention), the schema
  is the wire contract, not the JPA model — no lazy-loading or persistence detail leaks into the docs.
