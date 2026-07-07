<!--
========================================================================
FRONT-END INTEGRATION DOC — TEMPLATE + AUTHORING INSTRUCTIONS FOR CLAUDE
========================================================================
This block is instructions for Claude. It is an HTML comment, so it does
NOT render in the final doc. When you generate a real feature doc:
  1. Copy this file to docs/frontend/<feature-slug>.md
  2. DELETE this entire comment block from the copy.
  3. Fill every section below from the ACTUAL code — never invent shapes.

WHO READS THE OUTPUT
  A front-end developer who does NOT read Java and does NOT have the repo
  open. They need: what the feature does, the exact requests/responses they
  send and receive, how each endpoint behaves, what can go wrong, and any
  behaviour that recently CHANGED. Nothing else. No Java, no internal class
  names, no package paths, no "the service layer validates…". Wire-level only.

TONE & LENGTH
  Short and dense. Prefer a table or a JSON block over a paragraph. If a
  sentence doesn't help someone write a fetch() call, cut it. One doc = one
  feature (e.g. "Browse movies", "Create seats", "Charge a payment"), which
  may span several endpoints.

HOW TO DERIVE THE FACTS (do not guess — read these)
  • Endpoints & verbs .... the @RestController / @RequestMapping / @GetMapping
                           etc. Document the GATEWAY path the FE actually
                           calls: services expose bare paths (e.g. /movies),
                           the gateway adds the /api prefix and StripPrefix=1
                           removes it. So the FE calls /api/movies. Confirm the
                           prefix from the gateway routes before writing it.
  • Request body ......... the @RequestBody DTO (a record). Every field, its
                           JSON type, and whether it's required — read the bean
                           validation annotations (@NotNull/@NotBlank/@Min/…).
  • Query params ......... the *Filter DTO bound on the method + Pageable.
  • Response body ........ the returned DTO's fields (…Dto / …Response record).
                           Show the JSON the client receives, not the entity.
  • List responses ....... ALWAYS the PagedModel envelope (see the reusable
                           block in §Endpoints). Never a bare array. Document
                           page/size/sort, the default sort, and the WHITELISTED
                           sortable fields (the SORTABLE_FIELDS set in the
                           service) — an off-list sort is a 400, tell them.
  • Auth ................. state per endpoint. Auth is enforced at the gateway
                           (Keycloak/JWT), not in the service, so read the
                           gateway/security config. If Identity isn't wired yet
                           for this feature, say "None yet (enforced later at
                           gateway)" rather than omitting the row.
  • Errors .............. the <Service>ErrorCode enum for this service. List the
                           codes THIS feature's endpoints can return, the HTTP
                           status each maps to, and when each fires. All errors
                           come back as application/problem+json with a `code`
                           field the FE branches on (see §Errors block).
  • Behaviour changes .... only if something changed. If a specific code/status
                           or endpoint contract changed, record it in §Changelog
                           with the date and what the FE must do differently.
                           If nothing changed, delete the §Changelog section.

CONSISTENCY RULES (keep every doc identical in shape)
  • Keep the section order below. Delete a section only if it truly has no
    content (e.g. no request body on a GET) — and then delete its heading too.
  • Money is an integer in the smallest unit unless the DTO says otherwise —
    state the unit. Timestamps: state the format (ISO-8601 UTC).
  • Every example must be COPY-PASTE runnable: real header names, real field
    names, plausible values. Use the same base URL placeholder {{baseUrl}}.
  • Pages are 0-indexed. Say so once, in §Endpoints.
  • Don't document endpoints outside this feature's scope.
========================================================================
-->

# <Feature name> — Front-End Integration

> **Service:** <catalog | booking | payment | …> · **Base URL:** `{{baseUrl}}` (gateway) · **Last updated:** YYYY-MM-DD

## Summary
<2–4 sentences: what this feature is, what a FE dev builds with it (e.g. "the
movie-browsing page", "the seat-creation dashboard"), and any one thing they
must know up front. No internals.>

## Auth
| Endpoint | Auth required | Role / scope | Token header |
|---|---|---|---|
| `GET /api/...` | No / Yes | — / `<role>` | `Authorization: Bearer <jwt>` |

<If nothing is protected yet: "None yet — enforced later at the gateway.">

## Endpoints

> Base URL is the gateway. Paths below are what the FE calls (`/api/...`).
> All list responses use the **paged envelope** shown at the bottom of this
> section. Pages are **0-indexed**.

### `<VERB> /api/<path>` — <one-line purpose>
<1–2 sentences on what it does and when to call it.>

**Request**
- Headers: `<Header-Name>: <when/why>` (omit the row if none beyond auth)
- Path params: `<name>` — <type, meaning>
- Query params (list endpoints):

  | Param | Type | Default | Notes |
  |---|---|---|---|
  | `page` | int | `0` | 0-indexed |
  | `size` | int | `20` | max `100` |
  | `sort` | string | `<field,dir>` | whitelisted: `<f1>`, `<f2>` — others → 400 |
  | `<filter>` | <type> | — | <what it filters; absent = no filter> |

- Body (`application/json`) — for POST/PUT/PATCH:

  | Field | Type | Required | Notes / constraints |
  |---|---|---|---|
  | `<field>` | `<json type>` | yes/no | `<@Min 1, non-blank, unit, …>` |

  ```json
  { "<field>": "<value>" }
  ```

**Response** — `<200 OK | 201 Created | 402 …>`

```json
{ "<field>": "<value>" }
```

<Repeat one `### VERB /api/...` block per endpoint in this feature.>

---

**Paged envelope** (shape of every list response):

```json
{
  "content": [ /* array of the item shape documented above */ ],
  "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 }
}
```

## Behaviour notes
<Bullet list of contract behaviours a FE dev can't see from the schema alone.
Examples: "Retrying POST with the same `Idempotency-Key` replays the original
result and status." · "A declined charge is `402`, not an error — read the body."
· "An empty filter returns the full paged list, not an error." Delete this
section if there's nothing non-obvious.>

## Errors
All errors return `Content-Type: application/problem+json`. Branch on the
**`code`** field, not the HTTP status or the message text.

```json
{
  "type": "about:blank",
  "title": "<title>",
  "status": 400,
  "detail": "<human-readable, may change — do not parse>",
  "code": "<SERVICE_ERROR_CODE>",
  "instance": "/api/<path>"
}
```

| `code` | HTTP | When it fires |
|---|---|---|
| `<SERVICE>_VALIDATION_ERROR` | 400 | Bad body, bad filter, or off-whitelist `sort`. |
| `<SERVICE>_NOT_FOUND` | 404 | <resource> id doesn't exist. |
| `<...>` | <status> | <condition> |

## Changelog
<Only if a behaviour changed. One row per change. Delete the section otherwise.>

| Date | Endpoint / code | What changed | What the FE must do |
|---|---|---|---|
| YYYY-MM-DD | `<VERB /api/...>` or `<CODE>` | <before → after> | <action> |