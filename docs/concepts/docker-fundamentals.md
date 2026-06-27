# Docker fundamentals — images, layers, containers, commands

> The companion to [containers-and-compose.md](containers-and-compose.md). That file is about
> running *many* containers together with Compose; **this** file is the ground floor: what Docker
> actually is, the **layer/cache model** that explains every "why is my build slow / why did it
> rebuild everything", the `Dockerfile` instructions we use, and a command reference you can come
> back to. Read this first if Docker is rusty.

## 1. What it is

Docker packages an application **plus everything it needs to run** (JRE, libraries, OS userland)
into a single immutable artifact — an **image** — and runs that image as an isolated process — a
**container**. "It works on my machine" stops being a problem because the machine *travels with the
app*.

A container is **not a virtual machine.** A VM virtualises hardware and boots a whole guest OS
(its own kernel, gigabytes, ~minutes). A container is just a **normal Linux process** on the host
kernel, fenced off with kernel features (namespaces = "you can only see your own processes/network";
cgroups = "you get this much CPU/RAM"). That's why a container starts in milliseconds and a JRE
image is ~200 MB, not 2 GB.

```
   Virtual Machine                     Container
 ┌───────────────────┐            ┌───────────────────┐
 │  your app         │            │  your app         │
 │  guest OS + kernel│            │  (shares host     │
 │  (heavy, slow)    │            │   kernel)         │
 ├───────────────────┤            ├───────────────────┤
 │  hypervisor       │            │  Docker engine    │
 ├───────────────────┤            ├───────────────────┤
 │  host OS / kernel │            │  host OS / kernel │
 └───────────────────┘            └───────────────────┘
```

## 2. The three nouns: image, container, registry

| Term | What it is | Analogy |
|---|---|---|
| **Image** | The immutable, read-only build artifact (your jar + a JRE base + config). Built once from a `Dockerfile`. | A **class** / a frozen template / a `.iso`. |
| **Container** | A *running instance* of an image. Add a thin writable layer on top and start the process. Many containers can run from one image. | An **object** / a running program from that `.iso`. |
| **Registry** | Where images are stored and shared. Docker Hub (public), GHCR, AWS ECR (private). `docker pull`/`docker push` move images to/from it. | **Maven Central / a git remote**, but for images. |

"Many containers from one image" is exactly horizontal scaling — `docker compose up --scale notification=2`
runs two `notification` containers from the same image (we use this in Phase 4 to test dedupe).

## 3. Layers — the single most important concept

An image is **not one blob. It is a stack of read-only layers**, one per instruction in the
`Dockerfile`. Each layer records the filesystem *diff* that instruction produced. Docker stacks them
with a union filesystem so the running container sees one merged tree.

```
Dockerfile instruction            ->  layer (filesystem diff)
FROM eclipse-temurin:21-jre       ->  [ base: JRE + minimal OS ]   (shared by every Java image you build)
WORKDIR /app                      ->  [ mkdir /app ]
COPY app.jar app.jar              ->  [ + /app/app.jar ]
ENTRYPOINT ["java","-jar",...]    ->  [ metadata, no files ]
```

Two properties fall out of this, and they explain almost everything you'll wonder about Docker:

### 3a. Layers are cached and reused — **order matters**
When you rebuild, Docker walks the instructions top-to-bottom and reuses the **cached** layer for
each instruction **until it hits one whose inputs changed**. From that point down, every layer is
rebuilt. So you put **what changes least at the top, what changes most at the bottom.**

This is *the* reason a naive Java Dockerfile is slow. Compare:

```dockerfile
# BAD: any source change re-downloads all Maven dependencies
COPY . .                 # your code changed -> this layer changes...
RUN ./mvnw package        # ...so this re-runs from scratch, re-fetching every dependency

# GOOD: dependencies are their own cached layer
COPY pom.xml .            # changes rarely
RUN ./mvnw dependency:go-offline   # cached as long as pom.xml is unchanged
COPY src ./src            # changes often
RUN ./mvnw package        # only this re-runs on a code change; deps came from cache
```

(Our multi-stage Dockerfiles — §5 — use `-pl <module> -am` rather than the split above, because the
multi-module reactor needs the sibling poms; the principle is the same: cache the expensive step.)

### 3b. Layers are shared *across* images — deduplicated on disk
If ten of your images all start `FROM eclipse-temurin:21-jre`, that base layer is **stored once** and
shared. That's why our six service images don't cost 6 × 200 MB — they share the JRE base. A layer is
identified by a content hash (digest), so identical layers are the same layer everywhere.

### 3c. The container's writable layer is throwaway
A running container adds **one thin writable layer** on top of the image's read-only stack. Anything
the process writes (logs, temp files, a database's data files) goes there — and **vanishes when the
container is removed.** This is why Postgres data must live in a **volume** (§7), not in the
container's writable layer.

## 4. The Dockerfile — instruction by instruction

A `Dockerfile` is the recipe Docker reads to build an image. The instructions we actually use:

| Instruction | What it does | Note |
|---|---|---|
| `FROM image:tag` | Start from a base image (its layers become your bottom layers). | First instruction. `AS name` labels a stage (§5). |
| `WORKDIR /app` | Set the working directory for following instructions (and `cd` into it at runtime). | Creates the dir if missing. |
| `COPY src dst` | Copy files from the **build context** (§6) into the image. | Each `COPY` is a layer — order for cache. |
| `RUN cmd` | Execute a command **at build time**, bake the result into a layer. | e.g. compiling, installing packages. |
| `EXPOSE 8080` | **Documentation** that the app listens on a port. | Does *not* publish it — that's `-p` / compose `ports:`. |
| `ENV K=V` | Set an environment variable in the image. | Overridable at run time with `-e` / compose `environment:`. |
| `ENTRYPOINT ["java","-jar","/app.jar"]` | The command that runs **when the container starts**. | Use the JSON ("exec") form so signals (Ctrl-C, `docker stop`) reach the JVM. |
| `CMD [...]` | Default arguments / default command. | `ENTRYPOINT` + `CMD` combine; we just use `ENTRYPOINT`. |

**`RUN` vs `ENTRYPOINT/CMD` — the build/run split that trips everyone up:** `RUN` happens *once, at
`docker build` time*, and its output is frozen into a layer. `ENTRYPOINT`/`CMD` happen *every time a
container starts*. `RUN ./mvnw package` builds the jar into the image; `ENTRYPOINT java -jar` runs it
later. Mixing these up ("why does it rebuild every time I start it?") is the classic mistake.

## 5. Multi-stage builds — what our six Dockerfiles do

Problem: to *build* a Spring jar you need Maven + a full JDK (~600 MB of tooling). To *run* it you
only need a JRE (~200 MB). You don't want the build tooling shipped in your final image — it's dead
weight and extra attack surface.

**Multi-stage build:** use several `FROM` stages in one Dockerfile. Earlier stages build; the final
stage `COPY --from=<stage>` only the artifact it needs. Everything in the discarded stages is **left
behind** — not in the final image.

```dockerfile
# ---- stage "build": has Maven + JDK 21, compiles the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .                                          # the whole reactor (root build context, §6)
RUN mvn -q -pl <module> -am clean package -DskipTests
#        mvn (not ./mvnw) = the base image already ships Maven, and .dockerignore excludes the
#                           wrapper jar from the context, so we use the image's own Maven
#        -pl <module>  = build only this service
#        -am           = "also make" the modules it depends on
#        -DskipTests   = the reactor build already gates tests; the image just packages

# ---- stage "runtime": slim JRE, gets ONLY the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/<module>/target/<module>-*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java","-jar","/app.jar"]
```

The final image contains the JRE + your jar and **none** of Maven, the JDK, the `.m2` cache, or the
source. Smaller, faster to ship, less to attack — and the whole thing is reproducible from
`docker build` with no host Maven step. (See the plan
[2026-06-27-compose-full-stack.html](../plans/2026-06-27-compose-full-stack.html).)

## 6. Build context — the folder Docker can see

`docker build` (or compose `build:`) sends a **build context** — a folder tree — to the Docker
engine, and `COPY` can only copy from inside it. Two consequences:

- **Bigger context = slower build.** Everything in the context is tarred and sent first. That's why
  we add a **`.dockerignore`** (like `.gitignore`) to exclude `target/`, `.git/`, `.idea/` — they'd
  bloat the context and could leak host build artifacts into the image.
- **Our context is the repo root, not the module folder.** A normal single-app Dockerfile uses
  `build: ./booking` (context = that folder). But our `booking` Dockerfile needs the **parent
  `pom.xml`** and the **`mvnw` wrapper**, which live at the repo root. So in compose each service uses
  `build: { context: ., dockerfile: booking/Dockerfile }` — context = repo root, Dockerfile path
  pointed at the service. This is the one deviation from the concept-doc shorthand.

## 7. Volumes, networks, ports — the runtime plumbing

These are runtime concerns (set per `docker run` / per compose service), not baked into the image:

- **Volumes** — persistent storage that **outlives the container's throwaway writable layer** (§3c).
  A named volume (`pgdata:/var/lib/postgresql/data`) is where Postgres keeps its files so
  `docker compose down` + `up` doesn't wipe your database. `down -v` *does* delete volumes (a clean
  slate). Rule of thumb: **any data you want to survive a container restart goes in a volume.**
- **Networks** — Compose puts every service on a private virtual network where **the service name is
  a DNS name**. `booking` reaches its database at `booking-db:5432` and the registry at
  `discovery:8761` with zero IP config. (Same idea as Kubernetes Service DNS — see
  [service-discovery.md](service-discovery.md).)
- **Ports** — containers talk to each other freely *inside* the network. To reach a container **from
  your host (laptop) browser**, you must **publish** a port: `-p 8080:8080` (or compose
  `ports: ["8080:8080"]`) maps host-8080 → container-8080. We publish only the gateway (8080), the
  Eureka dashboard (8761), and the RabbitMQ/Zipkin consoles — the backend services stay internal.

## 8. Command reference

### Images
```bash
docker build -t myapp:1 .                 # build image "myapp:1" from ./Dockerfile, context = .
docker build -f svc/Dockerfile -t svc .   # explicit Dockerfile, context = repo root (our case)
docker images                             # list local images
docker pull postgres:16                   # fetch an image from a registry
docker rmi myapp:1                         # remove an image
docker image prune                        # delete dangling (untagged) images — reclaim disk
```

### Containers
```bash
docker run -d -p 8080:8080 --name g myapp:1   # run detached, publish a port, name it
docker ps                                  # running containers   (-a = include stopped)
docker logs -f g                           # follow a container's logs
docker exec -it g sh                       # open a shell inside a running container
docker stop g   /  docker start g          # stop / start (we 'stop payment' in Phase 5 for resilience)
docker rm g                                # remove a stopped container (its writable layer is gone)
```

### Compose (the whole system — see [containers-and-compose.md](containers-and-compose.md))
```bash
docker compose up --build                  # build images + start every service
docker compose up -d                       # detached (background)
docker compose ps                          # status of the stack
docker compose logs -f booking             # follow one service
docker compose exec catalog-db psql -U postgres -d catalog   # run a command in a service
docker compose up --scale notification=2   # two notification instances
docker compose down                        # stop + remove containers/network (KEEP volumes)
docker compose down -v                     # ...and delete volumes too (wipe databases)
```

### Housekeeping (disk fills up over time)
```bash
docker system df                           # how much disk images/containers/volumes use
docker system prune                        # remove stopped containers, unused networks, dangling images
docker system prune -a --volumes           # aggressive: also unused images AND volumes (careful)
```

## 9. How we use it here
- **Six multi-stage Dockerfiles** (§5), one per service, all built from the **repo-root context** (§6).
- **Three Postgres containers** each with a **named volume** (§7) so data survives restarts;
  database-per-service made physical.
- **Compose network DNS** (§7) is how `service name = DNS name` works (`booking-db`, `discovery`).
- **Published ports** only at the edge (gateway 8080) and on dashboards (8761/15672/9411).
- We **`docker stop` a service on purpose** (Phase 5) to watch the circuit breaker react.

## 10. Gotchas / interview lens
- **"Container vs VM?"** — container = isolated process on the host kernel (namespaces + cgroups), no
  guest OS; VM = virtualised hardware + full guest kernel. Containers are lighter and start instantly.
- **"Why is my Docker build slow / why did it rebuild everything?"** — layer cache invalidation (§3a):
  a change high in the `Dockerfile` busts every layer below it. Order least-changing first; split the
  dependency download from the source copy.
- **"Where does container data go?"** — the writable layer, which is **deleted with the container**.
  Persistent data needs a **volume** (§3c, §7). This is the #1 "my database reset itself" bug.
- **`EXPOSE` does not publish a port** — it's documentation; publishing is `-p` / `ports:` (§7).
- **`RUN` is build-time, `ENTRYPOINT` is run-time** (§4) — confusing them is the classic mistake.
- **Use the JSON/exec form of `ENTRYPOINT`** so `docker stop` delivers SIGTERM to the JVM for a clean
  shutdown (the shell form swallows the signal).
