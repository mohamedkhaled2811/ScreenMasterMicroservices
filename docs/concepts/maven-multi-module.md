# Maven multi-module (the reactor)

## What it is
A single Maven build made of **many modules**: one **parent POM** that aggregates them, plus one **child module per thing you ship**. The parent has `<packaging>pom</packaging>` (it builds *no* code), lists its children in `<modules>`, and centralises versions. Each child is a normal jar with its own `pom.xml` that points back at the parent. Maven builds the whole set in one pass called the **reactor**, working out the right order from the dependencies between modules.

In this repo the parent is the root `pom.xml` (`screenmaster-parent`) and the children are `discovery`, `gateway`, `catalog`, `booking`, `payment`, `notification`.

## Why it exists
We're splitting one monolith into services. Each service must be **independently buildable and deployable** — that's the whole point of microservices. A multi-module layout gives us:

- **One place for versions.** The parent pins Spring Boot, the Spring Cloud BOM, Lombok, etc. Children list dependencies *without* version numbers, so every service stays on the same, consistent set. Bump once, not six times.
- **Honest, lean boundaries.** Each child pulls *only* the starters it actually uses. The gateway has no JPA; payment has no database driver. The classpath of a service is the truth about what it depends on — not a shared "god classpath" everyone inherits.
- **One command, all services.** `./mvnw package` at the root builds every module; `./mvnw -pl payment package` builds just one. That ability to build/run a single service in isolation is what later lets us `docker stop payment` and watch Booking's circuit breaker open.

## The key distinction: `dependencyManagement` vs `dependencies`
This trips everyone up, so pin it down:

- **`<dependencyManagement>`** (in the parent) = *"if a child asks for this dependency, here's the version it gets."* It pulls **nothing** by itself. It's a version catalogue.
- **`<dependencies>`** (in a child) = *"I actually use this — put it on my classpath."* The child omits the version; Maven looks it up in the parent's `dependencyManagement` (or the imported BOM).

Same idea for plugins: **`<pluginManagement>`** in the parent configures a plugin *if a child declares it*; the child still has to opt in.

```
parent pom.xml  (packaging=pom)
├─ <modules>            ← who's in the reactor
├─ <dependencyManagement> → Spring Cloud BOM import (versions only, pulls nothing)
└─ <pluginManagement>     → Lombok annotation-processor + boot jar config

catalog/pom.xml  (jar)
├─ parent = screenmaster-parent
└─ <dependencies>      ← web-mvc, data-jpa, postgresql, eureka-client, lombok
                          (no <version> tags — inherited from the parent)
```

## How we use it here
- Root `pom.xml`: `packaging=pom`, the six `<modules>`, the Spring Cloud BOM in `dependencyManagement`, and Lombok + the Spring Boot jar plugin in `pluginManagement`. It still *inherits* `spring-boot-starter-parent` for sane defaults.
- Each child declares only its real dependencies (gateway ≠ catalog ≠ payment) and points its `<parent>` at `screenmaster-parent`.
- **Phase 0 caveat:** the children are empty — no `@SpringBootApplication` yet — so they do **not** declare `spring-boot-maven-plugin`. Its `repackage` goal needs a main class and would fail on an empty module. We add the plugin (and the boot app) to each service in **Phase 1**, when it becomes runnable. For now each module builds as a plain jar, which is enough to prove the scaffold (BUILD_PLAN step 0.3).

## Gotchas / interview lens
- **Aggregation ≠ inheritance.** They're two separate relationships that usually (but needn't) coincide. `<modules>` in the parent = *aggregation* (build these together, the reactor). `<parent>` in the child = *inheritance* (take config/versions from there). A module can be aggregated without inheriting, or inherit a parent that isn't its aggregator.
- **The reactor orders the build for you.** If `booking` depended on a shared library module, Maven builds the library first. It topologically sorts modules by their inter-dependencies — you don't hand-order `<modules>`.
- **`-pl` / `-am`.** `-pl <module>` ("project list") builds just that module; `-am` ("also make") additionally builds the modules it depends on. `./mvnw -pl discovery -am package` is our smoke test for the scaffold.
- **`packaging=pom` builds no code.** A parent/aggregator deliberately has no `src/`. Putting a `@SpringBootApplication` at the aggregator root is a smell — each service owns its own.
- **One bumped version, everywhere.** "How do you keep six services on the same Spring Cloud version?" → BOM import in the parent's `dependencyManagement`; children are version-free. That's the answer an interviewer is listening for.
