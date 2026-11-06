# Savings Account API — take-home exercise

A Spring Boot REST API that creates and reads savings bank accounts.

- Java 21, Spring Boot 3.5.x, Gradle (Kotlin DSL, wrapper committed)
- PostgreSQL (single table), Redis cache in front of reads with graceful degradation
- RFC 9457 problem details for every failure mode

Documentation:

| Doc | Contents |
|---|---|
| `docs/ARCHITECTURE.md` | components, request flows, persistence & caching model |
| `docs/DESIGN-TRADEOFFS.md` | every assumption and decision, with alternatives considered |

## Prerequisites

- JDK 21
- Docker (for Postgres + Redis, and for the Testcontainers-based test suite)

## Run locally

```bash
docker compose up -d     # Postgres on :5432, Redis on :6379
./gradlew bootRun        # API on :8080
```

Configuration is env-overridable: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`.

Health: `GET /actuator/health`.

## API

### Create an account

```bash
curl -sS -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"customerName": "Alice Tester", "nickname": "rainy day"}' -i
```

- `201 Created`, `Location: /api/v1/accounts/{id}`, account body incl. server-generated
  `id` and `accountNumber`
- `nickname` optional, 5–30 chars when present, screened against an offensive-term list
- `400` on validation failure (RFC 9457, field-level issues), `409` when the customer
  already holds 5 accounts, `503` when the datastore is unavailable (retryable)

### Fetch an account

```bash
curl -sS localhost:8080/api/v1/accounts/1 | jq
```

- `200` served from Redis when cached (10-min TTL), Postgres otherwise; a dead Redis
  degrades silently to Postgres
- `404` problem document for unknown ids

## Test

```bash
./gradlew test
```

Runs unit tests plus Testcontainers suites (real Postgres + Redis): end-to-end flow,
concurrent quota enforcement, Redis-outage degradation, Postgres-outage 503s.

## Project layout

```
src/main/java/com/takehome/savings/
├── api/          REST layer: controller, DTOs, validation annotations, error mapping
├── config/       cache configuration
├── domain/       SavingsAccount entity (single table)
├── repository/   Spring Data JPA + advisory-lock guard
└── service/      business rules, account-number generation, offensive-word filter
```
