# Error handling with ProblemDetail (RFC 9457)

## What it is
A **single, consistent error contract** for a service's HTTP API: every failure — a bad request, a missing resource, a downstream outage, an unexpected bug — comes back as an RFC 9457 **`ProblemDetail`** (`Content-Type: application/problem+json`) carrying a stable, machine-readable **`code`**. One `@RestControllerAdvice` produces it; controllers and services just *throw*.

## Why it exists
Without a central handler, a service returns whatever Spring's default error path emits:

```json
{ "timestamp": "...", "status": 400, "error": "Bad Request", "path": "/payments" }
```

There's no field a *caller* can branch on. The wording of `error` can change between Spring versions; the HTTP status alone is too coarse (a 400 could be a dozen different problems). In a microservice system that's a real bug: Booking's [saga](saga-pattern.md) calls Payment synchronously and must tell "the card was declined" (retrying won't help) apart from "the provider is unreachable" (retry later) apart from "I sent a malformed request" (fix the request). It branches on a **code**, not on prose or a bare status.

`ProblemDetail` is the web standard for this (RFC 9457, the successor to RFC 7807) and is **built into Spring 6+ / Boot 3+** — no library, no hand-rolled `ApiError` DTO to maintain. We add one custom property, `code`, and let the standard carry the rest (`type`, `title`, `status`, `detail`, `instance`).

## The three pieces
1. **An error-code enum** — the contract. Each constant pins the HTTP status it maps to, so status and code can never drift apart:

   ```java
   public enum PaymentErrorCode {
       PAYMENT_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
       PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
       PAYMENT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider unavailable"),
       PAYMENT_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
       // ... status() + title()
   }
   ```

2. **Domain exceptions** carrying a code — a base `PaymentException(PaymentErrorCode, message)` plus thin subclasses (`PaymentNotFoundException`, `PaymentProviderException`). The code travels *on* the exception so the handler needs no `instanceof` ladder.

3. **One `@RestControllerAdvice`** extending `ResponseEntityExceptionHandler`, so it also intercepts the **framework's** errors (bean validation, a missing header, an unreadable body) and gives *them* a `code` too — not just our own throws:

   ```java
   private ProblemDetail problemDetail(PaymentErrorCode code, String detail) {
       ProblemDetail p = ProblemDetail.forStatusAndDetail(code.status(), detail);
       p.setTitle(code.title());
       p.setProperty("code", code.name());   // the custom member callers branch on
       return p;
   }
   ```

The wire result:

```json
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "amount: must be greater than 0",
  "code": "PAYMENT_VALIDATION_ERROR",
  "instance": "/payments"
}
```

## How we use it here (Payment is the reference)
`payment/.../exception/` holds all three pieces: `PaymentErrorCode`, `PaymentException` + subclasses, and `GlobalExceptionHandler`. It maps:
- `@Valid` body violations (`MethodArgumentNotValidException`) and method-level header constraints (`HandlerMethodValidationException`), plus a missing `Idempotency-Key` (`MissingRequestHeaderException`) → `PAYMENT_VALIDATION_ERROR` (400).
- an infrastructural provider failure, wrapped by the service as `PaymentProviderException` → `PAYMENT_PROVIDER_UNAVAILABLE` (503). Note a normal **`DECLINED`** is *not* an error — it's a successful charge with a negative answer, returned as a `402` from the controller, never thrown.
- anything unanticipated → `PAYMENT_INTERNAL_ERROR` (500) with a safe message; the real exception is logged, never leaked.

This is the per-service convention (see `CLAUDE.md` → Conventions): every service gets its own `exception/` package with this same shape. It also feeds [observability](observability.md) — 5xx codes are logged with a stack trace, 4xx at `warn` without the noise — and gives the [saga](saga-pattern.md) and [resilience](resilience-patterns.md) layers (Phase 5: retry/circuit-breaker) a clean signal to branch on.

## Gotchas / interview lens
- **Status code is not a contract; a stable `code` is.** "We return RFC 9457 `ProblemDetail` with a custom `code` property. Callers branch on the code — adding a code is backwards-compatible, renaming one is a breaking change — so a reworded message or a re-mapped status never silently breaks a consumer."
- **Business outcome vs error.** A declined payment is a *successful* call with a business answer (402), not a 5xx exception. Throwing for expected outcomes pollutes error rates and makes retries fire when they shouldn't.
- **Extend `ResponseEntityExceptionHandler`, don't just add `@ExceptionHandler`s.** Otherwise framework errors (validation, bad JSON, missing params) bypass your advice and still return the default body with no `code`.
- **Never leak internals.** The catch-all returns a generic message and logs the detail server-side — a stack trace in a response body is an information-disclosure smell.
- **Don't catch-and-return in controllers.** Throw a coded exception; let the one advice translate it. Error mapping lives in exactly one place.
