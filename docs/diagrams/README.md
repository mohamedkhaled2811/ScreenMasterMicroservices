<!--
========================================================================
DIAGRAM AUTHORING SPEC — INSTRUCTIONS FOR CLAUDE
========================================================================
This whole file is the standing spec for authoring ScreenMaster diagrams.
It is NOT copied per diagram (unlike docs/frontend/_template.md) — you read
it, then produce the diagram trio described in §Output. Read THIS file to
author any diagram; do NOT load docs/diagrams/template-reference.excalidraw
(40 KB of JSON) — every value you need is in the tables below. The reference
.excalidraw/.png are only the rendered exemplar of "what good looks like".

WHO READS THE OUTPUT
  A developer reading this repo to LEARN microservices. A diagram is a
  teaching aid, not decoration: it must pass "could someone learn something
  concrete from this?" If it only labels boxes, it has failed — redraw it.
========================================================================
-->

# ScreenMaster Diagrams — authoring spec

> **Reading this to draw a diagram? Do not open `template-reference.excalidraw`.** Everything you need is
> here. That file (and its `.png`) is only the rendered exemplar — the palette table below is authoritative;
> **if the exemplar ever disagrees with a table in this file, the table wins.**

Every ScreenMaster diagram exists to **teach a backend/microservices concept to a developer reading the
repo**. JSON mechanics (element shapes, bindings, arrows) are owned by the **`excalidraw-diagram` skill** —
invoke it for structure. *This* file owns the ScreenMaster **style contract** and the **required content**
for each diagram type.

---

## Step 0 — Which type am I drawing? (decision rule)

- **Overview of a service, or of the whole system, and the patterns it uses** → **High-level** (§High-level).
- **One pattern, and how it is applied in the code** → **Low-level** (§Low-level).

If the request fits neither, ask before drawing.

---

## The style contract (applies to BOTH types)

Fixed for every diagram, no exceptions:

| Property | Value |
|---|---|
| Canvas (`viewBackgroundColor`) | `#fefdf9` (cream) |
| `roughness` | `1` on shapes/arrows/titles (sketchy, handwritten); `0` on small caption/note text |
| `fontFamily` | `3` (handwritten) everywhere |
| `strokeWidth` | `2` on shapes and primary arrows; `1` on lifelines/dividers |
| `opacity` | `100` on everything (never use transparency for hierarchy — use size/color) |
| `fillStyle` | `"solid"` |
| Shape corners | rectangles `"roundness": {"type": 3}` |

### Semantic palette — color MEANS something (authoritative)

Never invent colors. Pick the pair whose **meaning** matches the element's role.

| Role (meaning) | Stroke | Fill | Use for |
|---|---|---|---|
| **Start / edge** | `#c2410c` | `#fed7aa` | Gateway, user entry, external trigger, the caller at the edge |
| **Service / process** | `#1e3a5f` | `#93c5fd` | a running service; an action/step inside one |
| **Success / datastore** | `#047857` | `#a7f3d0` | a database, a confirmed/done state, a successful result |
| **Decision** | `#b45309` | `#fef3c7` | a branch / condition (diamond) |
| **Async event / queue** | `#6d28d9` | `#ddd6fe` | RabbitMQ, a published event, an async hop |
| **Error / compensate** | `#b91c1c` | `#fecaca` | failure, rollback, compensation, a decline |

Text colors: titles `#9a3412`; section headers use their section's role stroke; muted captions/notes
`#a8a29e` or `#57534e`.

**Stroke style encodes the hop:** `solid` = synchronous call; `dashed` = **async / compensating** hop
(events, queue publishes, degraded/rollback paths). This is how a reader tells a REST call from an event
without reading a word.

### Shape → meaning

| Concept | Shape |
|---|---|
| Service, process, action step | `rectangle` (rounded) |
| Start / input / end / result | `ellipse` |
| Decision / condition | `diamond` |
| Datastore | `rectangle` (green role) |
| Sequence lifeline, divider | `line` (dashed, `strokeWidth` 1) |
| Label, caption, annotation | free-floating `text` (no container) |

Rendered exemplar of the palette + a saga assembled from it: **`template-reference.png`** (view it to see
what "good" looks like; do not read its JSON).

---

## High-level diagrams — overview of a service (or the whole system)

**Two granularities:** *single-service* (one service + its patterns) or *whole-system* (all services +
gateway + Eureka at once, like the exemplar saga). Grammar: **boxes + arrows** (the style above).

**Required content — all six:**

1. **The subject as box(es)** — the service (single) or every service + gateway + Eureka (system), each in
   its role color (service = blue; gateway/edge = orange).
2. **Edges** — what calls in (orange), and what it calls out to: sibling services (blue), its own database
   (green), RabbitMQ (purple). Every relationship is an arrow; position alone never implies a link.
3. **Patterns named on the diagram** — the patterns the service embodies, each as a short label (e.g.
   `API composition`, `Outbox`, `Idempotent consumer`, `Saga`). Names only in the picture — the
   `docs/concepts/` links live in the sidecar (keeps the diagram clean).
4. **Sync vs async** — solid arrows for REST calls, dashed for events/queue hops.
5. **A one-line caption** stating the single concept the reader should leave with.
6. **Concept cross-links** — in the **sidecar**, each named pattern → its `docs/concepts/<file>.md`.

---

## Low-level diagrams — one pattern, applied in the code

Grammar: **flow only** (top-to-bottom boxes + arrows — the same visual language as high-level, so the repo
reads as one system). Do **not** use a formal sequence/lifeline grammar.

**Verify names against the actual code first.** Before drawing, read the relevant repo source and use its
**real** class / method / table / endpoint names. This is a learning repo — a diagram that teaches fiction
is worse than no diagram. (Example done right: `BookingService.myBookings` → `bookingRepository.findByUserId`
→ `catalogClient.titlesByIds` → `GET /movies/batch?ids=…`.)

**Required content — all seven:**

1. **Exactly one pattern**, named; its `docs/concepts/` file linked in the sidecar.
2. **The code flow** — the real steps in order, as flow boxes; solid = sync, dashed = async hop.
3. **Real names as evidence** — actual classes/methods/tables/endpoints from *this* repo, not placeholders.
   (The `excalidraw-diagram` skill calls these "evidence artifacts" and requires them for technical diagrams.)
4. **A "why" callout** — one free-floating note naming the failure this pattern prevents or the cost it pays
   (e.g. "batch = the network N+1 fix", "degrade → list still renders with `title: null`").
5. **Semantic colors** — datastore green, async purple, error/degrade red (+ dashed), edge orange.
6. **Caption + concept link** — in the sidecar.
7. **Highlight where the pattern LIVES** — visually mark the step(s) that *are* the pattern (the pattern's
   role color + a note/label like "← API composition here"), so the reader's eye separates the pattern from
   the surrounding plumbing (the controller, the repo read, the DTO merge). This is the point of a low-level
   diagram: not "what does this flow do" but "here is the pattern, in the code".

---

## Output — the diagram trio (all committed to git)

Every diagram is three files that travel together and are **committed** (PNGs included — there is no ignore
rule against them):

| File | What |
|---|---|
| `<name>.excalidraw` | the source |
| `<name>.png` | the render (see §Render) |
| `<name>.md` | the **sidecar** (see skeleton below) |

**Where they live:**

- **Service-scoped** (single-service high-level + *every* low-level) → **`<service>/docs/diagrams/`**, so a
  developer reading the module sees the diagram beside its code.
- **Whole-system** high-level (no single owning service) → **root `docs/diagrams/`**.
- This spec + the reference exemplar → always **root `docs/diagrams/`**.

**Naming:** type prefix + subject (+ pattern for low-level): `high-<subject>` / `low-<subject>-<pattern>`.
e.g. `high-booking`, `high-system`, `low-booking-api-composition`.

### Sidecar `.md` skeleton

Fixed order; delete a section only if it is genuinely empty (and delete its heading too). Section 4 is
**low-level only**.

```markdown
# <Title> — <one-line caption: the single concept to walk away with>

![<alt>](<name>.png)

## What to learn here
<2–4 sentences: what the diagram teaches and why it matters for microservices.>

## Patterns → concepts
- **<Pattern name>** → [concepts/<file>.md](../../../docs/concepts/<file>.md)

## Code anchors            <!-- low-level only -->
- `<service>/src/main/java/.../<File>.java` — <what it is in the flow>
```

(Adjust the `../` depth on the concept links to the real location of the file — service-scoped diagrams sit
deeper than root ones.)

---

## Render & verify (MANDATORY — the `.png` is a deliverable)

You cannot judge a diagram from JSON. After generating/editing the `.excalidraw`, render it, **Read the
PNG**, and fix what you see — loop until it's clean (no clipped/overlapping text, arrows land right, spacing
even, teaches at a glance).

```bash
cd .claude/skills/excalidraw-diagram/references && uv run python render_excalidraw.py <path-to>.excalidraw
```

The PNG lands next to the `.excalidraw`. **First run on a fresh machine** needs the renderer's browser once:

```bash
cd .claude/skills/excalidraw-diagram/references && uv run playwright install chromium
```

Only when the rendered PNG passes is the diagram done — then it, the source, and the sidecar are committed.
