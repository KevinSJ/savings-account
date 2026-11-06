# Savings Account API — take-home exercise

Spring Boot REST API that creates and reads savings bank accounts.

- Java 21 / Spring Boot 3.5.x / Gradle (Kotlin DSL)
- PostgreSQL (single table), Redis cache in front of reads

## Quick start

```bash
docker compose up -d      # Postgres + Redis
./gradlew bootRun         # API on :8080
```

_(full runbook arrives with the docs commit)_

## Test

```bash
./gradlew test            # unit + Testcontainers integration suites
```
