# docs/plans/ — feature plans

Before implementing a feature in this repo, we write a **plan first** as an HTML file here,
review it together, and only then write code. This folder is the review surface.

## Why HTML (not Markdown)
The plan presents **multiple options side-by-side with trade-offs**. HTML lets us render
option cards, comparison tables, and "what changes" diagrams that are easier to scan and
compare in a browser than a flat Markdown list. Open the file directly in a browser to review.

## Convention
- **One file per feature**, named `YYYY-MM-DD-<feature-slug>.html`
  (e.g. `2026-06-24-catalog-service.html`).
- Start from [`_template.html`](_template.html) — copy it, fill in the sections.
- The plan is committed to git so the decision history is reviewable. (It is *not* gitignored.)
- Files starting with `_` (like `_template.html`) are scaffolding, not feature plans.

## Workflow
1. We agree on a feature.
2. Claude **asks** "want a plan for this?" (trivial one-liners can skip — Claude says so when skipping).
3. Claude writes `docs/plans/YYYY-MM-DD-<slug>.html` with:
   - **What changes** — files/modules/schema/endpoints touched.
   - **Options** — 2+ approaches, each with pros/cons and a recommendation.
   - **Milestone link** — which field-guide milestone (M1–M7) and schema-doc section this serves.
   - **Open questions** — anything Claude needs decided before coding.
4. Mohamed reviews in the browser and picks an approach.
5. Only then does implementation start. If the chosen scope changes, the plan file is updated.

## Required sections (mirrored in `_template.html`)
| Section | Purpose |
|---|---|
| Summary | One-paragraph "what and why". |
| Milestone & schema link | Ties the feature to the curriculum. |
| What changes | Concrete list of files/modules/migrations/endpoints. |
| Options | The heart of the plan — approaches with trade-offs + a recommendation. |
| Implementation tasks | The chosen option as an ordered breakdown — each task has a goal + done-when. |
| Risks / costs felt | The microservices cost this exercises (missing JOIN, distributed txn, partial failure…). |
| Verification | How the change is proven end-to-end — command + expected result per row. |
| Open questions | Decisions needed before coding. |