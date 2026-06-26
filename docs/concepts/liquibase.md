# Liquibase — versioned database schema migrations

## What it is
A **schema migration tool**: the database's structure is defined by an ordered list of **changesets** (in YAML/XML/SQL) that Liquibase applies in sequence and records, so every environment converges on the exact same schema. It's the database equivalent of committing your schema to source control instead of editing it by hand.

## Why it exists
The monolith uses Hibernate's `ddl-auto=update` — Hibernate inspects your entities and **edits the live schema** to match. That's convenient and dangerous:
- It's **non-deterministic across instances**: five service instances all booting against one database can race to alter it.
- It **only ever adds** — it won't drop a column, rename safely, backfill data, or run a data migration.
- There's **no history**: you can't see what changed, when, or roll a release's schema forward/back deliberately.

A migration tool makes the schema a **reviewed, versioned artifact**: each change is a file in the repo, applied exactly once, in order, recorded in the database itself.

## The mechanics
Liquibase keeps two bookkeeping tables in each database:
- **`DATABASECHANGELOG`** — one row per applied changeset (id, author, checksum, timestamp). On boot it applies only the changesets not already recorded here.
- **`DATABASECHANGELOGLOCK`** — a mutex so two instances booting at once don't both try to migrate.

A **changeset** is immutable once applied: Liquibase stores its checksum, and editing it later fails the build. New changes are **new changesets appended** to the log, never edits to old ones — that's what guarantees every environment ends up identical.

```yaml
# db/changelog/db.changelog-master.yaml — the master is an INDEX of changeset files, in order
databaseChangeLog:
  - include:
      file: db/changelog/changes/001-create-payments.yaml
```
```yaml
# db/changelog/changes/001-create-payments.yaml — one real change
databaseChangeLog:
  - changeSet:
      id: 001-create-payments
      author: mohamed
      changes:
        - createTable:
            tableName: payments
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true,
                          constraints: { primaryKey: true, nullable: false } }
              - column: { name: idempotency_key, type: VARCHAR(120),
                          constraints: { nullable: false, unique: true } }
              # … status, provider_reference, amount, created_at
```

## How we use it here
- **One master changelog per DB-owning service** at `src/main/resources/db/changelog/db.changelog-master.yaml`, pointed at by `spring.liquibase.change-log`. Spring Boot runs it automatically on startup (Liquibase is on the classpath).
- Paired with **`spring.jpa.hibernate.ddl-auto=validate`**: Hibernate no longer touches the schema — it only *checks* that the entities match what Liquibase built, and refuses to boot if they've drifted. **Liquibase owns the schema; the entity is validated against it.**
- **`payment`** ships a real first changeset now (the `payments` table). **`catalog`** and **`booking`** start with an **empty** master (`databaseChangeLog: []`) — that still wires Liquibase (it creates the two tracking tables) so the tool is proven before Phase 2/3 adds their first real tables as `include`d changeset files.
- **Tests don't run Liquibase**: the hermetic `contextLoads` tests use in-memory H2 with `spring.liquibase.enabled=false` and let Hibernate `create-drop` the test schema (the Postgres-flavoured changesets aren't needed there).

## Liquibase vs Flyway
Both are migration tools solving the same problem; the project picked Liquibase. Flyway is **SQL-first** (numbered `V1__*.sql` files); Liquibase is **changeset-first** and **database-agnostic** (declare *intent* in YAML/XML — `createTable`, `addColumn` — and it generates the right SQL per database, with built-in rollback support). For a learning project that may target different databases and wants reviewable, self-documenting changes, the declarative format earns its keep. (You'll see "Flyway" in interview talk as the common alternative — know that they're interchangeable in *purpose*.)

## Gotchas / interview lens
- **Never edit an applied changeset** — Liquibase stores its checksum; changing it fails validation. Fix-forward with a new changeset.
- **`validate` is the safety net** — if an entity and the migrated schema disagree, the app won't start. That catches "I changed the `@Entity` but forgot the migration" at boot, not in production.
- **Empty changelog is legitimate** — it still creates `DATABASECHANGELOG`/`DATABASECHANGELOGLOCK`, so the plumbing is verified before any real table exists.
- **One writer at a time** — the lock table is why a rolling deploy of N instances doesn't corrupt the schema; the first to grab the lock migrates, the rest wait.
- Interview phrasing: *"`ddl-auto=update` is convenient but non-deterministic and history-less; I version the schema with Liquibase changesets and run Hibernate in `validate` mode, so the migration is a reviewed artifact and the app refuses to boot if the entities and schema have drifted."*
