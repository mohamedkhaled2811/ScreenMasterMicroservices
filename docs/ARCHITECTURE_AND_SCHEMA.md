# ScreenMaster — Current Architecture & Schema Reference

> **Purpose of this document:** Capture the *current* (monolithic) design of ScreenMaster as a single source of truth to drive a migration to microservices. It documents the data model, entity relationships, API surface, async messaging, security, external integrations, and a proposed service decomposition with the hard problems (shared tables, distributed transactions, FK cuts) called out.

- **Stack:** Spring Boot 3.5.4, Java 21, Spring Web (MVC) + WebFlux (`WebClient`), Spring Data JPA, Spring Security (JWT), PostgreSQL, RabbitMQ, Thymeleaf (email), SpringDoc OpenAPI.
- **Build:** Maven (`pom.xml`), `mvnw` wrapper.
- **Base package:** `com.gr74.ScreenMaster`
- **DB:** single PostgreSQL database `screenmaster`, `ddl-auto=update` (Hibernate manages schema — **no migration tool** like Flyway/Liquibase yet).

---

## 1. High-level layout (monolith)

```
src/main/java/com/gr74/ScreenMaster
  ├─ config/          # SecurityConfig, JwtFilter, RabbitMQConfig, WebClientConfig, PaypalConfig, DotenvConfig
  ├─ controller/      # REST endpoints
  ├─ service/         # business logic + integrations
  ├─ repository/      # Spring Data JPA repos
  ├─ model/           # JPA entities (the schema below)
  ├─ dto/{request,response}/
  ├─ enums/           # status & type enums (persisted as STRING in several places)
  ├─ specification/   # dynamic JPA filtering (MovieSpecification)
  └─ exception/       # GlobalExceptionHandler, BusinessErrorCodes, ExceptionResponse
src/main/resources
  ├─ application.properties
  └─ templates/email/ # Thymeleaf HTML email templates
```

Cross-cutting concerns currently coupled in one deployable:
- **Auth** (JWT issuance + filter) — `JwtFilter`, `JwtService`, `AuthenticationService`.
- **Async email** via RabbitMQ — producer in auth flow, consumer sends SMTP mail.
- **Scheduled TMDB sync** — `TMDBScheduledTasks`, `TMDBSyncService`, `SyncStatus` table.
- **PayPal payments** — `PaypalService`, `PaymentService`, webhook controller.

---

## 2. Data model / schema

### 2.1 Entity catalogue

All persistent entities live in `com.gr74.ScreenMaster.model`. Most extend `BaseEntity` (auditing timestamps).

| Entity | Table | PK | PK strategy | Notes |
|---|---|---|---|---|
| `Movie` | `movies` | `id` (int) | **assigned** (TMDB id) | id comes from TMDB, not generated |
| `Genre` | `geners` *(sic — typo in DB)* | `id` (Integer) | assigned (TMDB id) | |
| `Theater` | `theaters` | `id` (Integer) | `@GeneratedValue` | unique `name` |
| `Screen` | `screens` | `id` (Integer) | `@GeneratedValue` | unique `(name, theater_id)` |
| `Seat` | `seats` | `id` (Integer) | `@GeneratedValue` | unique `(screen_id, seat_number, row_number)` |
| `SeatType` | `seat_types` | `id` (Integer) | `@GeneratedValue` | unique `name`; `priceMultiplier` |
| `Showtime` | `showtimes` | `id` (int) | `@GeneratedValue` | unique `(screen_id, movie_id, show_date, show_time)` |
| `Booking` | `bookings` | `id` (int) | `@GeneratedValue` | `bookingReference`, `expiresAt` |
| `BookingSeat` | `booking_seats` | `id` (int) | `@GeneratedValue` | unique `(booking_id, seat_id)` |
| `PaymentTransaction` | `payments` | `id` (String) | **assigned** (PayPal payment id) | unique `booking_id`, unique `transaction_id` |
| `User` | `_user` | `id` (Integer) | `@GeneratedValue` | implements `UserDetails`, `Principal`; unique `email` |
| `Role` | `role` | `id` (Integer) | `@GeneratedValue` | unique `name` |
| `VerificationCode` | `verification_codes` | `id` (Long) | `IDENTITY` | `code`, `expiresAt`, `used`, `type` |
| `SyncStatus` | `sync_status` | `id` (Long) | `@GeneratedValue` | unique `syncType`; sync bookkeeping |

> **Migration flag — assigned PKs:** `Movie`, `Genre`, and `PaymentTransaction` use externally-assigned IDs (TMDB / PayPal). This is convenient for a Catalog/Payment service boundary because IDs are stable and externally sourced.

### 2.2 Relationships

```
Theater 1───* Screen 1───* Seat *───1 SeatType
                 │
Movie *───* Genre  (join table: movie_genres)
                 │
Movie 1──────────┼──* Showtime *──────1 Screen
                          │
                 Showtime 1───* Booking *───1 User
                                   │
                          Booking 1───* BookingSeat *───1 Seat
                                   │
                          Booking 1───1 PaymentTransaction
User *───* Role  (default JPA join table)
User 1───* Booking
```

Explicit JPA associations (FK columns):
- `Movie ⇄ Genre`: `@ManyToMany` join table **`movie_genres`** (`movie_id`, `genre_id`).
- `Theater → Screen`: `@OneToMany(mappedBy="theater")`, cascade ALL, orphanRemoval.
- `Screen → Theater`: `@ManyToOne` FK `theater_id` (`@JsonBackReference`).
- `Screen → Seat`: `@OneToMany(mappedBy="screen")`, cascade ALL, orphanRemoval (`@JsonIgnore`).
- `Seat → Screen`: `@ManyToOne` FK `screen_id`.
- `Seat → SeatType`: `@ManyToOne` FK `seat_type_id`.
- `Showtime → Movie`: `@ManyToOne(LAZY)` FK `movie_id` (NOT NULL).
- `Showtime → Screen`: `@ManyToOne(LAZY)` FK `screen_id` (NOT NULL).
- `Booking → User`: `@ManyToOne` FK `user_id`.
- `Booking → Showtime`: `@ManyToOne(LAZY)` FK `showtime_id` (NOT NULL).
- `BookingSeat → Booking`: `@ManyToOne(LAZY)` FK `booking_id` (NOT NULL).
- `BookingSeat → Seat`: `@ManyToOne(LAZY)` FK `seat_id`.
- `PaymentTransaction → Booking`: `@ManyToOne(LAZY)` FK `booking_id` (NOT NULL, **unique** → effectively 1:1).
- `User ⇄ Role`: `@ManyToMany(EAGER)` (default join table).
- `User → Booking`: `@OneToMany` (note: unidirectional, default mapping).

### 2.3 BaseEntity (auditing)

`BaseEntity` (`@MappedSuperclass`) adds `createdDate` (`@CreatedDate`, not updatable) and `lastModifiedDate` (`@LastModifiedDate`). Extended by `Movie`, `Theater`, `Screen`, `Seat`, `SeatType`, `User`. **Note:** the `@Id` in `BaseEntity` is commented out — each entity declares its own id.

> Some entities (`Showtime`, `Booking`, `BookingSeat`, `Role`, `VerificationCode`) declare auditing fields directly with `@CreatedDate`/`@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)` instead of extending `BaseEntity`. Inconsistent but functionally similar.

### 2.4 Enums (domain vocabulary)

| Enum | Values | Used by | Persisted as |
|---|---|---|---|
| `BookingStatus` | PENDING, CONFIRMED, CANCELLED, EXPIRED | `Booking.bookingStatus` | ordinal (no `@Enumerated(STRING)`) ⚠️ |
| `PaymentStatus` | CREATED, APPROVED, COMPLETED, FAILED, CANCELLED | `Booking.paymentStatus`, `PaymentTransaction.paymentStatus` | `Booking`: ordinal ⚠️ / `Payment`: STRING |
| `PaymentMethod` | PAYPAL, STRIPE, CARD | `PaymentTransaction.paymentMethod` | STRING |
| `ShowtimeStatus` | SCHEDULED, CANCELLED, COMPLETED, SOLD_OUT | `Showtime.status` | STRING |
| `ScreenType` | Front_Screen, Rear_Projection, Screen_3D | `Screen.screenType` | ordinal ⚠️ |
| `CodeType` | EMAIL_VERIFICATION, PASSWORD_RESET | `VerificationCode.type` | STRING |

> ⚠️ **Migration flag:** enums stored as **ordinal** (default) are fragile — reordering values silently corrupts data. Before splitting services, convert these to `@Enumerated(EnumType.STRING)` (requires a data migration) so the value is part of a stable cross-service contract.

---

## 3. API surface (REST endpoints)

Base context path is root (`/`). Auth is JWT bearer except where noted. Currently **only `/auth/**` and Swagger paths are public** (`SecurityConfig`); every other route requires authentication (the commented-out `permitAll` blocks show intended public read paths that are not active).

### Authentication — `AuthenticationController` (`/auth`) — public
| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register` | Register user → triggers async verification email |
| GET | `/auth/verify` | Verify via link/code |
| POST | `/auth/verify` | Verify via posted code |
| POST | `/auth/resend-verification` | Resend code |
| POST | `/auth/authenticate` | Login → returns JWT |

### Bookings — `BookingController` (`/bookings`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/bookings` | Create booking (reserves seats, computes total) |
| GET | `/bookings/reference/{reference}` | Lookup by booking reference |
| GET | `/bookings/user/{userId}` | Bookings for a user |
| GET | `/bookings/showtime/{showtimeId}` | Bookings for a showtime |
| PATCH | `/bookings/{bookingId}/status` | Update booking status |
| PATCH | `/bookings/{bookingId}/payment` | Update payment status (COMPLETED ⇒ CONFIRMED) |
| DELETE | `/bookings/{bookingId}` | Cancel booking |

### Showtimes — `ShowTimeController` (`/show-time`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/show-time` | Create showtime |
| GET | `/show-time/{id}` | Get one |
| GET | `/show-time/movie/{movieId}` | By movie |
| GET | `/show-time/movie/upcoming/{movieId}` | Upcoming by movie |
| GET | `/show-time/screen/{screenId}` | By screen |
| DELETE | `/show-time/{id}` | Delete |

### Theater / Screens / Seats / SeatTypes — `TheaterController` (`/theater`)
| Method | Path | Purpose |
|---|---|---|
| GET | `/theater/get-theaters` | List theaters |
| POST | `/theater/add-theater` | Create theater |
| DELETE | `/theater/delete-theater/{theaterId}` | Delete theater |
| GET | `/theater/{theaterId}/screens` | Screens of theater |
| POST | `/theater/add-screen` | Add screen |
| DELETE | `/theater/screen/{screenId}` | Delete screen |
| GET | `/theater/get-screen-seats/{screenId}` | Seats of screen |
| POST | `/theater/add-screen-seat` | Add seat |
| DELETE | `/theater/seat/{seatId}` | Delete seat |
| GET | `/theater/get-seat-types` | List seat types |
| POST | `/theater/add-seat-type` | Add seat type |
| DELETE | `/theater/delete-seat-type/{seatTypeId}` | Delete seat type |

### Movies & Genres
| Method | Path | Controller | Purpose |
|---|---|---|---|
| POST | `/movies/filter` | `MoviesController` | Filter/sort movies (JPA Specification) |
| GET | `/genres/` | `GenresController` | List genres |

### Payments — PayPal
| Method | Path | Controller | Purpose |
|---|---|---|---|
| GET | `/payment/create/{bookingId}` | `PaypalController` | Create PayPal payment, redirect to approval |
| GET | `/payment/success` | `PaypalController` | PayPal return URL (execute payment) |
| GET | `/payment/cancel` | `PaypalController` | Cancel return URL |
| GET | `/payment/error` | `PaypalController` | Error return URL |
| POST | `/paypal/webhook` | `PaypalWebhookController` | Async PayPal webhook (status updates) |

---

## 4. Asynchronous messaging (RabbitMQ)

Config in `RabbitMQConfig` + `application.properties`. Uses a **TopicExchange** with JSON message conversion (`Jackson2JsonMessageConverter`).

| Component | Value |
|---|---|
| Exchange | `screenmaster-exchange` (topic) |
| Queue: verification | `vqueue` ← routing key `email-verification-key` |
| Queue: booking | `bqueue` ← routing key `booking-key` |

Flows:
- **Email verification (active):** `RabbitMQProducerService.sendVerificationMessage(VerificationEmailDto)` → exchange/`email-verification-key` → `vqueue` → `RabbitMQConsumerService.consumeVerificationMessage(...)` → `EmailService.sendVerificationEmail(...)` (SMTP + Thymeleaf template).
- **Booking queue (`bqueue`/`booking-key`):** declared but no producer/consumer wired yet — reserved for booking-event notifications. Good seam for a future Notification service.

Message payload `VerificationEmailDto`: `{ to, code, verificationUrl }`.

---

## 5. Security

- **JWT auth** (`io.jsonwebtoken` 0.11.5). `JwtService` issues/validates tokens; `JwtFilter` (a `OncePerRequestFilter` before `UsernamePasswordAuthenticationFilter`) populates the `SecurityContext`.
- **`User` implements `UserDetails` + `Principal`**; authorities derive from `Role.name` via `SimpleGrantedAuthority`.
- `BCryptPasswordEncoder` for password hashing.
- `@EnableMethodSecurity(securedEnabled = true)` — method-level role checks available.
- Config values: `application.security.jwt.secret-key`, `application.security.jwt.expiration` (in `application.properties` — **secret is hard-coded there; move to a secret manager for prod/microservices**).
- Public matchers: `/auth/**`, OpenAPI/Swagger paths. Everything else authenticated.

---

## 6. External integrations

### TMDB (The Movie DB)
- `TMDBApiService` (via `WebClient`, base URL `https://api.themoviedb.org/3`, API key `tmdb.api.key`).
- `TMDBSyncService` + `TMDBScheduledTasks` (Spring `@Scheduled`) import/update movies & genres in batches.
- `SyncStatus` table tracks per-`syncType` (`POPULAR`, `TOP_RATED`, `NOW_PLAYING`, …): `lastSync`, `lastPage`, `totalPages`, `status` (`RUNNING`/`COMPLETED`/`FAILED`), `errorMessage`.
- DTOs: `TMDBMovieResponse`, `TMDBMovieListResponse`, `TMDBGenreResponse`, `TMDBGenreListResponse`.
- Scheduler pool size 5; Hikari pool max 20 (tuned for concurrent sync).

### PayPal
- `rest-api-sdk` 1.14.0 + `paypal-core`. `PaypalConfig` builds the API context (`paypal.client-id`, `paypal.client-secret`, `paypal.mode=sandbox`).
- `PaypalService` creates/executes payments; `PaymentService` persists `PaymentTransaction` (keyed by PayPal payment id), enforces "one successful payment per booking" via `existsByBookingIdAndPaymentStatusIn([APPROVED, COMPLETED])`.
- Webhook (`/paypal/webhook`) updates payment/booking status out-of-band.

### Email (SMTP)
- Gmail SMTP (`smtp.gmail.com:587`, STARTTLS). `EmailService` + `EmailTemplateService` render Thymeleaf templates under `templates/email/`.

### Config loading
- `DotenvConfig` (`dotenv-java`) loads `.env` for secrets (`spring_mail_username`, `tmdb_api_key`, `paypal_client_*`, etc.) referenced via `${...}` in `application.properties`.

---

## 7. Key business logic to preserve in a split

**Booking creation (`BookingService.createBooking`) — the critical transaction:**
1. Load `User` and `Showtime`.
2. Validate seat selection non-empty.
3. **Double-booking guard:** `bookingSeatRepository.countActiveBookingsByShowtimeIdAndSeatIdIn(showtimeId, seatIds) > 0` ⇒ reject.
4. Load `Seat`s, verify all exist.
5. **Pricing:** `total = Σ (showtime.basePrice × seat.seatType.priceMultiplier)`.
6. Create `Booking` (`PENDING`/`CREATED`), `bookingReference = "BK-" + 8 uppercase hex`, `expiresAt = now + 15 min`.
7. Persist `Booking` then `BookingSeat`s.

This single `@Transactional` method spans **Showtime, Seat, SeatType, User, Booking, BookingSeat** — five would-be service boundaries. It is the hardest thing to decompose (see §8.3).

**Payment confirmation:** `updatePaymentStatus(bookingId, COMPLETED)` flips `Booking.bookingStatus → CONFIRMED`. The double-booking check relies on "active" bookings, so booking expiry (the 15-min `expiresAt`) and a sweeper to expire stale `PENDING` bookings are load-bearing for seat availability correctness. *(No expiry sweeper job is visible in the current code — flag as a gap.)*

---

## 8. Proposed microservices decomposition

### 8.1 Candidate services (by bounded context)

| Service | Owns (tables) | Responsibilities | Today's code |
|---|---|---|---|
| **Identity / Auth** | `_user`, `role`, `verification_codes`, `user_roles` | register, login, JWT issuance, email verification | `AuthenticationService`, `JwtService`, `JwtFilter`, `User`, `Role`, `VerificationCode` |
| **Catalog (Movies)** | `movies`, `geners`, `movie_genres`, `sync_status` | movie/genre catalog, TMDB sync, filter/search | `MoviesService`, `GenresService`, `TMDB*`, `MovieSpecification` |
| **Theater / Inventory** | `theaters`, `screens`, `seats`, `seat_types` | theaters, screens, seat layout, seat types | `TheaterService`, `ScreenService`, `SeatService`, `SeatTypeService` |
| **Scheduling (Showtimes)** | `showtimes` | showtime CRUD, availability | `ShowtimeService` |
| **Booking** | `bookings`, `booking_seats` | seat reservation, pricing, expiry, double-booking guard | `BookingService` |
| **Payment** | `payments` | PayPal/Stripe, webhooks, payment status | `PaymentService`, `PaypalService`, webhook |
| **Notification** | (none / its own) | email/SMS, RabbitMQ consumers, templates | `EmailService`, RabbitMQ consumer |

Plus infrastructure: **API Gateway** (route `/auth`, `/bookings`, …), **Service Discovery / Config**, and a **JWT validation** strategy shared across services (gateway validates, or each service validates with a shared public key — prefer asymmetric RS256 over the current shared HMAC secret).

### 8.2 FK cuts created by the split (relationships that become cross-service references)

These JPA associations cross proposed boundaries and must become **IDs + API/event lookups**, not DB foreign keys:

- `Showtime → Movie` (Scheduling → Catalog) ⇒ store `movieId`.
- `Showtime → Screen` and `Seat`/`Screen`/`SeatType` (Scheduling/Booking → Theater) ⇒ store `screenId`, `seatId`, `seatTypeId`.
- `Booking → User` (Booking → Identity) ⇒ store `userId` (from JWT).
- `Booking → Showtime` (Booking → Scheduling) ⇒ store `showtimeId`.
- `BookingSeat → Seat` (Booking → Theater) ⇒ store `seatId` + snapshot of seat price/type at booking time.
- `PaymentTransaction → Booking` (Payment → Booking) ⇒ store `bookingId`.

> **Recommendation:** snapshot the values needed for correctness/audit into the consuming service (e.g. Booking stores `seatPrice`, `seatTypeName`, `showDate/time`) so it does not need a synchronous call to render or validate after the fact. The code already snapshots `seatPrice` into `BookingSeat` and `totalAmount` into `Booking` — extend this pattern.

### 8.3 The booking transaction (distributed-transaction problem)

`createBooking` currently does seat-availability check + reservation + pricing in one ACID transaction across what will become **Booking, Scheduling, and Theater** services. Options:

1. **Keep seat-reservation state inside Booking service.** Move the "is this seat taken for this showtime" check into Booking's own DB (it already queries `booking_seats`). Booking only needs *immutable facts* from Theater (seat exists, seatType multiplier) and Scheduling (showtime exists, basePrice) — fetch once and snapshot. This keeps the double-booking guard local and ACID. **Recommended.**
2. **Saga / outbox** for confirmation: Booking emits `BookingCreated` → Payment processes → emits `PaymentCompleted` → Booking confirms (already mirrors the existing `updatePaymentStatus` flow and the unused `bqueue`). Use the **transactional outbox** pattern to publish reliably.
3. Reuse `expiresAt` (15 min) as the saga timeout: a sweeper expires `PENDING` bookings and frees seats. **This sweeper must be built** (gap today).

### 8.4 Migration sequencing (suggested)

1. **Add Flyway/Liquibase** and pin the current schema (stop relying on `ddl-auto=update`).
2. **Fix enum persistence** → `@Enumerated(STRING)` everywhere (ordinal → string data migration) so values are stable contracts.
3. **Introduce an API Gateway** in front of the monolith; move JWT validation to the gateway / shared verifier (migrate HMAC secret → RS256 keypair).
4. **Carve off low-coupling, read-mostly contexts first:** Catalog (Movies/TMDB) and Notification (already async via RabbitMQ — easiest extraction).
5. **Extract Identity/Auth** (self-contained; others only need `userId` from the token).
6. **Extract Theater/Inventory and Scheduling** (define their query APIs/events).
7. **Extract Booking + Payment last** (highest coupling; needs the saga/outbox + expiry sweeper).
8. **Database-per-service:** split the single `screenmaster` DB; replace cross-context FKs with the ID references from §8.2.

### 8.5 Cross-cutting to build for microservices

- **Config & secrets:** move hard-coded JWT secret and `.env` values to a config server / secret manager.
- **Distributed tracing & correlation IDs** (currently `System.out.println` debugging in the consumer — replace with structured logging + tracing).
- **Resilience:** retry/circuit-breaker on inter-service and external (TMDB/PayPal) calls (`spring-retry` already present).
- **Idempotency:** PayPal webhook and RabbitMQ consumers must be idempotent (dedupe by `transactionId` / message id).
- **Eventing backbone:** RabbitMQ is already in place — promote it (or Kafka) to the inter-service event bus; the unused `bqueue`/`booking-key` is a ready seam.

---

## 9. Known gaps / cleanups worth doing before the split

- **No DB migration tool** — `ddl-auto=update` is unsafe across multiple services/instances.
- **Ordinal-stored enums** (`BookingStatus`, `Booking.paymentStatus`, `ScreenType`) — fragile.
- **No booking-expiry sweeper** despite `expiresAt` being set and relied upon for availability.
- **Hard-coded JWT secret** in `application.properties`.
- **`BookingRequestDto.userId`** is passed explicitly — the code TODO notes it should come from the authenticated principal; do this before extraction so Booking trusts only the token.
- **Table name typo** `geners` (vs `genres`) — rename during the Catalog extraction migration.
- Debug `System.out.println` in `RabbitMQConsumerService` — replace with logging.
- Booking `paymentStatus` is duplicated on both `Booking` and `PaymentTransaction` — decide the source of truth (Payment service) once split.

---

*Generated as a migration reference. Verify line-level details against source before acting; the schema is Hibernate-managed (`ddl-auto=update`) so the live DB is the ground truth.*