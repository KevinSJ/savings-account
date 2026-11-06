# Architecture

## Overview

A deliberately small, layered Spring Boot service: one business capability
(open/read savings accounts), one table, one cache.

```
            POST /api/v1/accounts         GET /api/v1/accounts/{id}
                 │                              │
                 ▼                              ▼
        ┌──────────────────────────────────────────────┐
        │                  api layer                   │
        │  AccountController · DTO records ·           │
        │  bean validation (@NotBlank/@Size/           │
        │  @Pattern/@NotOffensive) ·                   │
        │  GlobalExceptionHandler (RFC 9457)           │
        └───────────────┬──────────────────────────────┘
                        │ domain objects only, no ORM leakage
                        ▼
        ┌──────────────────────────────────────────────┐
        │                 service layer                │
        │  AccountService                              │
        │   ├─ per-customer advisory lock              │
        │   ├─ max-5-accounts rule                     │
        │   ├─ LuhnAccountNumberGenerator              │
        │   └─ OffensiveWordFilter (blocklist)         │
        └───────────────┬──────────────────────────────┘
                        ▼
        ┌──────────────────────────────────────────────┐
        │            repository (Spring Data JPA)      │
        │             single table savings_accounts    │
        └───────────────┬──────────────────────────────┘
                        ▼
                  PostgreSQL
                  (unique constraint on account number,
                   identity PK, optimistic-lock version)

        ── read path caching ─────────────────────────
        GET ──► Redis (@Cacheable, 10-min TTL, typed JSON)
                 │ on miss / Redis down
                 ▼
              Postgres (CacheErrorHandler swallows
              connectivity errors, never the query)
```

## Layers and why they exist

| Layer | Responsibility | Not allowed to |
|---|---|---|
| `api` | HTTP shape: routing, wire records, bean validation, error mapping | business decisions, JPA types on the wire |
| `service` | business rules, orchestration, generation | HTTP concepts |
| `domain` | entity + invariants that fit one row | know about repositories |
| `repository` | persistence, DB-specific statements (advisory lock) | business rules |
| `config` | cache wiring | anything else |

Build system: Gradle 9.8 Kotlin DSL; all dependency versions live in the
`gradle/libs.versions.toml` version catalog (Boot BOM applied via `platform()`,
no inline versions anywhere).

The dependency direction is strictly inward. `AccountResponse` and
`CreateAccountRequest` are records converted at the edge, so the wire contract can
evolve without touching the entity.

## Create flow (the interesting one)

```
POST /api/v1/accounts
  1. JSON decoded; bean validation on the record (mandatory name, optional
     nickname: 5..30 chars, @NotOffensive screen)
  2. AccountService.create (single Postgres transaction):
       a. set_config(lock_timeout=2s) + pg_advisory_xact_lock(upper(canonical name))
          <- serialises racing opens; bounded wait -> retryable 503 on contention
       b. countByCustomerNameIgnoreCase(name) >= 5  -> AccountLimitExceededException (409)
       c. generator yields 11 random digits + Luhn check digit
          (probe existsByAccountNumber, max 5 retries) -> AccountNumberAllocationException (503)
       d. INSERT; DB unique constraint on account_number is the final backstop
  3. 201 Created + Location: /api/v1/accounts/{id} + body
```

Steps 2a+2b are what make the quota race-free across app instances. A read-modify-write
(`count` then `insert` with no lock) allows two concurrent transactions to both observe
4 accounts and both insert - a phantom-read race that READ COMMITTED does not stop.

## Read flow

```
GET /api/v1/accounts/{id}   (also GET /api/v1/accounts/by-number/{accountNumber})
  1. Redis lookup (typed JSON, 10-min TTL, prefix savings:accounts:)
  2. miss -> Postgres select; not found -> 404 problem (404s are not cached)
  3. hit: deserialised without touching Postgres
  Redis outage: CacheErrorHandler logs and falls through to Postgres - the cache
  is an optimisation, never a dependency (ResilienceCacheDownIT proves this).
  Responses carry Cache-Control: no-store, private (personal data).
```

## Error model

All failures map to RFC 9457 `application/problem+json` with stable `type` URIs
(`https://api.takehome.co.nz/errors/<type>`) via one `@RestControllerAdvice`:

| Situation | Status | type |
|---|---|---|
| bean validation failures | 400 | `validation-failed` (+ `issues[]`) |
| well-formed but semantically wrong (e.g. bad account-number format) | 400 | `invalid-request` |
| unreadable/malformed JSON | 400 | `malformed-body` |
| non-numeric path id | 400 | `invalid-parameter` |
| unknown id | 404 | `account-not-found` |
| unknown route / method | 404 / 405 | `route-not-found` / `method-not-allowed` |
| quota exceeded | 409 | `account-limit-reached` |
| unique-violation (rare backstop) | 409 | `conflict` |
| Postgres unreachable / tx begin fails / timeout | 503 | `datastore-unavailable` (+ `retryable: true`) |
| advisory-lock wait expired (55P03 lock_timeout) | 503 | `contention` |
| account-number probe-insert race lost | 503 | `allocation-collision` |
| number allocation exhausted | 503 | `allocation-unavailable` |
| anything else | 500 | `internal-error` (logged, never leaked) |

The datastore-unavailable mapping exists because "database down" is a runtime
condition, not a crash: the app still boots, actuator health goes `DOWN` on the
`db` component, and requests degrade to a retryable 503 instead of stack traces
(`ResilienceDbDownIT` boots the app against dead ports to prove it).

## Data model

Single table `savings_accounts` (see `src/main/resources/db/schema.sql`):

| column | notes |
|---|---|
| `id` | identity PK, server-generated |
| `account_number` | unique, 12 digits ending in a Luhn check digit |
| `customer_name` | mandatory; canonicalised (NFKC + whitespace collapse); served by a functional index on upper(customer_name) |
| `nickname` | nullable; blank normalised to NULL |
| `created_at` / `updated_at` | audit stamps |
| `version` | optimistic-lock column, ready for a future mutation path |

Schema is owned by the application (`schema.sql`, `ddl-auto: none`). A real system
would carry it in Flyway/Liquibase instead - noted as a TODO.

## Packaging and delivery

The application ships as a Docker image, per project requirement: multi-stage
`Dockerfile` on Alpine bases - `eclipse-temurin:21-jdk-alpine` builds the jar,
`eclipse-temurin:21-jre-alpine` runs it. The Boot jar is extracted with
`jarmode=tools` so `lib/` (dependency jars) forms its own image layer; the
container runs as a non-root `spring` user with a curl-based healthcheck and
JVM tuned via `MaxRAMPercentage`. Compose wires the app to postgres/redis over
the internal network (service names, never host-mapped ports) and exposes
every host port as an overridable variable - dev boxes share 5432/6379.

## Testing pyramid

| Level | Suite | Runs against |
|---|---|---|
| unit | generators, offensive filter, service rules (Mockito) | nothing external |
| web slice | `AccountControllerWebTest` (MockMvc) | MockMvc, real validation stack |
| persistence | `SavingsAccountRepositoryIT` | Testcontainers Postgres |
| end-to-end | `AccountFlowIT`, `ConcurrentAccountLimitIT` | real HTTP + Postgres + Redis |
| resilience | `ResilienceDbDownIT`, `ResilienceCacheDownIT` | deliberately dead dependency ports |
| acceptance | `hurl/api.hurl` (18 requests) inside the hurl container image | the composed stack, real HTTP |
| contract | `openapi/savings-accounts-api.yaml` (hand-written, examples from live traffic) | reviewed against implementation |

Testcontainers use the singleton-container pattern (one Postgres + one Redis per
JVM, shared by all suites) to keep the build fast while still testing real
Postgres-specific behaviour (advisory locks, identity columns, unique constraints).
