# CI and code coverage

## What it is

**Continuous Integration (CI)** is the practice of automatically building and testing the code on every push, so the team knows the moment something breaks. **Code coverage** measures which lines and branches of production code were actually executed by the tests. Together they turn "it works on my machine" into an observable, repeatable gate.

## Why it exists

In a single-module monolith, one test run tells the whole story. In a multi-module reactor with six services, a refactor in one module can silently break another module's tests — or, worse, halt the reactor early and hide failures in modules that never got to run. CI exists to surface that decay immediately.

Coverage adds a **smoke detector**, not a grade: it points at code that has *no* automated execution path, which is a risk signal. It does not say the executed code is correct, well-designed, or meaningfully tested.

## Example

The pipeline in `.github/workflows/ci.yml`:

```yaml
- name: Run the reactor
  run: ./mvnw -B verify
```

This single command compiles every service, runs every test, packages every jar, and produces a JaCoCo coverage report for each module because the parent `pom.xml` binds JaCoCo's `prepare-agent` and `report` goals.

## How we use it here

* **GitHub Actions** runs `./mvnw -B verify` on every push and pull request, using JDK 21 and the built-in Maven cache.
* **Maven dependency caching** is handled by `actions/setup-java` with `cache: maven`. It restores `~/.m2/repository` between runs and invalidates the cache when any `pom.xml` changes. Because CI runs one reactor job, all six modules share the same local Maven repo during the build — a dependency is downloaded at most once per run.
* **JaCoCo** is configured once in the parent `pom.xml` and inherited by every module. The agent attaches during tests; the `report` goal runs at the `verify` phase and writes HTML/XML to `target/site/jacoco/`.
* **Coverage visibility** is GitHub-native: a Python step parses every `jacoco.xml` and writes a Markdown summary to the Actions run page, and `madrapps/jacoco-report` posts a per-module table as a PR comment.
* **Artifacts** are uploaded even when the build fails (`if: always()`), so a red build still leaves surefire reports and coverage HTML to inspect.
* **Coverage summary** is written to the GitHub Actions job summary on every push, so coverage is visible without leaving GitHub.
* **PR coverage report** is posted by `madrapps/jacoco-report` on every pull request. It reads each module's `jacoco.xml` and shows a per-module coverage table as a PR comment. No third-party service or token is required — it uses the built-in `GITHUB_TOKEN`.
* **No coverage gate yet.** We report the number and look at it before deciding on an honest threshold. Setting a floor on day one would fail services that are still smoke-test-only (gateway, discovery, payment, notification) and would encourage low-value tests written only to hit a percentage.

### Line vs branch coverage

| Metric | What it measures | Example |
|---|---|---|
| **Line coverage** | Was this source line executed? | A method body runs. |
| **Branch coverage** | Was each boolean path taken? | `if (status == PAID)` — did the test exercise both the `true` and `false` branches? |

A class can have 100% line coverage but low branch coverage if all `if`s always take the same path. Branch coverage is the more honest signal for logic-heavy code.

## Gotchas / interview lens

* **Coverage is necessary, not sufficient.** 80% coverage with weak assertions is worse than 50% coverage with strong ones. Treat it as "what is definitely not exercised" rather than "how good the tests are".
* **Bytecode instrumentation changes nothing semantically.** JaCoCo adds probes to class files at load time via a `-javaagent`. The application behaves the same; it just records which probes fired.
* **Self-invocation bypasses transaction proxies.** The test fix that rode along with this CI plan (`MovieTitleReadModelTest` importing `MovieTitleBackfiller`) is a real example: splitting `REQUIRES_NEW` into its own bean fixed a silent drop, but the test slice had to be updated too. Without CI, that break sat on `main`.
* **Independent deployability needs independent proof.** Six services that *can* deploy separately must also *build and test* separately — or at least together in a reactor gate. CI is what keeps that promise honest.
* **PR-only coverage comments.** `madrapps/jacoco-report` only posts a comment on pull requests. Direct pushes to `main` still show coverage via the Actions job summary, so nothing is lost.
* **The upgrade path.** Once the real coverage numbers are known, add `jacoco:check` with a per-module threshold that is intentionally a floor, not a target.
