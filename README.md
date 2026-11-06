# Savings Account API — take-home exercise

Spring Boot REST API that creates and reads savings bank accounts.

- Java 21, Spring Boot 4.1.2, Gradle 9.8 (Kotlin DSL + version catalog)
- PostgreSQL (single table), Redis cache in front of reads with graceful degradation
- RFC 9457 problem details for every failure mode
- Ships as an Alpine-based Docker image (app included, not just its dependencies)
- API contract: `openapi/savings-accounts-api.yaml`; acceptance suite: `hurl/`

Documentation:

| Doc | Contents |
|---|---|
| `docs/ARCHITECTURE.md` | components, request flows, persistence & caching model |
| `docs/DESIGN-TRADEOFFS.md` | every assumption and decision, with alternatives considered |
| `openapi/savings-accounts-api.yaml` | OpenAPI 3.1 spec of the implemented surface |
| `hurl/README.md` | the docker-run HTTP acceptance suite |

## Quick start (docker, everything containerised)

```bash
docker compose up -d --build    # app + Postgres + Redis
./hurl/run.sh                   # 18-request acceptance suite inside the hurl image
```

The API is on `${APP_HOST_PORT:-8080}`; Postgres/Redis host ports are
`${PG_HOST_PORT:-5432}` / `${REDIS_HOST_PORT:-6379}`.

## Quick start (host tooling)

```bash
docker compose up -d postgres redis   # or all three: docker compose up -d --build
./gradlew bootRun                     # API on :8080
```

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
curl -sS localhost:8080/api/v1/accounts/1 | jq          # by id
curl -sS localhost:8080/api/v1/accounts/by-number/325748979052 | jq   # by public number
```

Responses carry `Cache-Control: no-store, private` (personal data).

## Security posture (exercise scope)

There is no authn/authz: the exercise defines no security actors. That means, read
honestly: with sequential ids anyone who can reach the API can enumerate accounts
and their customer names. Mitigations in scope are the `by-number` public lookup
key and the no-store headers; the structural fixes (authentication, opaque
identifiers, field-level authorisation) are documented in
`docs/DESIGN-TRADEOFFS.md` (A7, future work) rather than faked here.

- `200` served from Redis when cached (10-min TTL), Postgres otherwise; a dead Redis
  degrades silently to Postgres
- `404` problem document for unknown ids

## Test

```bash
./gradlew test            # unit + Testcontainers integration suites (65 tests)
./hurl/run.sh             # acceptance suite against a running stack (docker)
```

Covers: end-to-end flow, concurrent quota enforcement, Redis-outage degradation,
Postgres-outage 503s, lock contention, leet-vector screening, plus the docker
acceptance suite asserting the OpenAPI contract over real HTTP.

## Project layout

```
src/main/java/com/takehome/savings/
├── api/          REST layer: controller, DTOs, validation annotations, error mapping
├── config/       cache configuration
├── domain/       SavingsAccount entity (single table)
├── repository/   Spring Data JPA + advisory-lock guard
└── service/      business rules, account-number generation, offensive-word filter
gradle/libs.versions.toml   single source of dependency versions
Dockerfile                  multi-stage alpine build of the app itself
openapi/                    OpenAPI 3.1 contract of the implemented surface
hurl/                       docker-run acceptance suite
```
