# CONTEXT — ScreenMaster glossary

> The project's ubiquitous language. Terms only — no implementation details, no plans.
> See [CLAUDE.md](CLAUDE.md) for how the project is built and [docs/concepts/](docs/concepts/) for pattern explainers.

## Diagrams

- **Diagram (teaching diagram)** — an Excalidraw drawing whose job is to teach a backend/microservices
  concept to a developer reading the repo. Not decoration; it must pass the "could someone learn something
  concrete from this?" test. Rendered as a `.excalidraw` file (plus a `.png` export).

- **High-level diagram** — a diagram that gives the *overview* of a service (or the whole system): the
  components, their edges, and the **patterns** each service embodies, named and cross-linked to
  `docs/concepts/`. Two granularities: **single-service** (one service + its patterns) and **whole-system**
  (all services + gateway + Eureka at once). Boxes-and-arrows ("flow") grammar.

- **Low-level diagram** — a diagram that shows *one pattern and how it is applied in the code*, using the
  real class/method/table/endpoint names verified against this repo. Flow grammar (top-to-bottom boxes +
  arrows), with the **pattern-defining steps highlighted** so a learner sees where the pattern actually
  lives versus the surrounding plumbing.

- **Style contract** — the fixed visual language every ScreenMaster diagram obeys: the semantic color
  palette (each color *means* something), roughness, cream canvas, fonts, and shape→meaning legend. Its
  single source of truth is the diagram authoring spec in `docs/diagrams/` (a Markdown file), **not** the
  reference `.excalidraw`.

- **Semantic palette** — the rule that color carries meaning, not decoration. Established pairs (stroke /
  fill): start/edge = orange (`#c2410c`/`#fed7aa`); service/process = blue (`#1e3a5f`/`#93c5fd`);
  success/datastore = green (`#047857`/`#a7f3d0`); decision = amber (`#b45309`/`#fef3c7`); async event/queue
  = purple (`#6d28d9`/`#ddd6fe`); error/compensate = red (`#b91c1c`/`#fecaca`). Solid stroke = sync hop;
  dashed stroke = async/compensating hop.

- **Diagram authoring spec** — the Claude-facing Markdown file in `docs/diagrams/` that carries the style
  contract and the required-content checklist for each diagram type. Claude reads *this* (not the 40 KB
  reference `.excalidraw`) to author a new diagram; the reference `.excalidraw` / `.png` remain only as the
  rendered exemplar of "what good looks like".

- **Sidecar** — the companion `.md` file paired with each generated `<name>.excalidraw`. It holds the
  caption, the "what to learn here", and the links from each named pattern to its `docs/concepts/` file
  (kept out of the diagram itself so the picture stays clean). Mirrors how the repo already pairs every
  plan and every frontend doc with a file.

- **Diagram trio** — a generated diagram is three files that travel together, all **committed to git**:
  `<name>.excalidraw` (source), `<name>.md` (sidecar), `<name>.png` (render). Storage rule: **service-scoped**
  diagrams (single-service high-level + every low-level) live in `<service>/docs/diagrams/`, so a developer
  reading a module sees the diagram beside its code; **whole-system** high-level diagrams (no single owning
  service) live in root `docs/diagrams/`. The authoring spec (`docs/diagrams/README.md`) and the reference
  exemplar always live in root `docs/diagrams/`. The sidecar embeds the render via a relative
  `![](<name>.png)` — one PNG file, both committed and shown inline. The sidecar's fixed skeleton: title +
  caption, "what to learn here", patterns→`docs/concepts/` links, the embedded PNG, and (low-level only)
  code anchors as `service/src/.../File.java` paths.
