# Design trade-offs and assumptions

The requirements leave real gaps. Each gap below states the assumption made, the
alternative(s) rejected, and why. Nothing here is load-bearing cleverness: every
choice can be swapped without touching the API contract.

## Stated assumptions (where the spec was insufficient)

| # | Assumption | Rationale |
|---|---|---|
| A1 | A "customer" is identified by their (trimmed) name, case-insensitively | The spec defines no customer entity. Name-as-identity is intentionally crude; a real system would carry a `customer_id` FK. The quota code centralises this in one place to make the swap trivial. |
| A2 | The 5-account limit is per customer across the whole system, not per session/request | "customer cannot create more than 5 accounts" reads as a policy about the customer, not about transport sessions. |
| A3 | Nickname checks apply only to the nickname, not the customer name | Spec says "ensure nickname not offensive". Applying the same screen to names is a one-line extension; name matching is riskier (false positives on real names). |
| A4 | A blank (`""`/whitespace) nickname is invalid, not "absent" | Absence is expressed as JSON `null` / omitted field. If a client sends `""` they probably meant something; forcing an explicit choice removes ambiguity. Absent nicknames are stored as SQL `NULL`, not empty strings. |
| A5 | No money/balance/transaction operations | The spec asks only for create + read. Building a ledger (postings, balance, interest) would triple the surface without a requirement - see "future work". |
| A6 | Account number format is internal-only (12 digits + Luhn) | No jurisdiction/IBAN scheme was specified. The generator is an interface; a country-specific scheme slots in behind it. |
| A7 | HTTP is the only access path; no authn/authz layer | No requirement mentions security actors. A real deployment would front this with the bank's identity fabric; adding half-baked JWTs here would be theatre. |

## Key decisions

### D1 - Quota enforcement: advisory lock + count (not just a count)

**Problem.** `SELECT count(*)` then `INSERT` is not atomic: under READ COMMITTED,
two concurrent creates for a customer with 4 accounts both count 4 and both insert
(phantom read). The bug is invisible in sequential tests and certain under load.

**Chosen:** `pg_advisory_xact_lock(hashtextextended(lower(name), 0))` inside the
same transaction as the count+insert. Sessions serialise per customer; the lock is
released at commit/rollback automatically. Deadlock-free: every create takes
exactly one lock before any read.

**Alternatives rejected:**
- *Serializable isolation + retry on 40001*: portable, but pushes retry policy into
  every caller and turns contention into visible latency spikes; correctness now
  depends on every code path remembering the retry.
- *Customer table + `SELECT ... FOR UPDATE`*: the textbook answer - but it requires
  inventing a customer entity the spec doesn't have, and row locks add index churn.
  The natural follow-up refactor (A1) should adopt it.
- *DB trigger counting rows on INSERT*: same phantom-read problem inside the
  trigger unless it also takes a lock; moves the logic away from readable code.
- *Unique constraint on `(customer_name, ordinal)`*: clever but obscure; ordinal
  assignment still races without a lock.

**Residual risk:** the key is the (hashed) name; two names hashing identically
would serialise unnecessarily - harmless (locking "too much" only costs throughput).
Hash collisions in `hashtextextended` are astronomically unlikely and benign in
effect. Case handling: the lock key lower()s the name to match the case-insensitive
count (proven by the mixed-case race in `ConcurrentAccountLimitIT`).

### D2 - Account numbers: random + Luhn, not a sequence

**Chosen:** 11 secure-random digits + Luhn check digit (12 total). Uniqueness probed
against the table (max 5 attempts), with the DB unique constraint as the last resort.

- vs. DB sequence: monotonic numbers leak business volume and invite enumeration;
  a fixed-format check digit also catches transposition typos before any lookup.
- vs. random UUID: 12 numeric digits are what customers can read over a phone call.
- Cost: collision retries. With a 10^11 space, expected attempts ~ 1.0; the retry cap
  converts pathology into a retryable 503 (`AccountNumberAllocationException`).

### D3 - Nickname offensiveness: local filter now, moderation service eventually

**Chosen:** a classpath blocklist + a three-pass matcher (exact tokens after NFKC
folding; a leetspeak-folded "skeleton" for glued obfuscation like `s-t-u-p-i-d`;
substring restricted to >=5-letter terms to blunt Scunthorpe-class false positives
- "hello" must not trip on "hell").

**Rejected / deferred:** calling an external content-moderation API from a bean
validator couples a validation hot path to network availability, and the spec asked
for "a list of offensive nick names". The word list ships tame on purpose: the
*mechanism* is the deliverable. TODO(moderation) marks the production path
(curated corpus + async review queue + the inevitable false-positive appeals).

### D4 - Caching: Redis in front of GET, degradation over durability

**Chosen:** Spring Cache abstraction + Redis; typed JSON serialisation bound to the
domain entity; 10-minute TTL; cache writes deferred to after-commit
(`transactionAware()`); connectivity errors in the error handler are logged and
swallowed so reads fall through to Postgres.

- **Why cache the entity:** all-scalar, no lazy relations, read-only usage - safe.
  If the entity ever grows relations or lazy state, move the cached shape to the
  wire record instead (documented seam).
- **Why TTL even without mutation endpoints yet:** the first `PATCH`/`PUT` will
  forget to invalidate something; the TTL bounds how long the bug can lie. Write
  paths should evict explicitly when they arrive.
- **404s are not cached** (`disableCachingNullValues` + exceptions propagate):
  unknown-id probes must not poison real keys.
- **Redis down at boot** still boots the app: RedisCacheManager connects lazily;
  the health endpoint shows `redis: DOWN` so operators see the truth while reads
  keep working.
- **Rejected:** caffeine-only local cache (not shared across instances; spec asked
  for Redis), caching at the controller (mixes concerns), null-caching (turns
  transient missing rows into ten-minute lies).

**Known limitation:** no cache-stampede protection (singleflight/locks). At this
write-read ratio and cardinality it is noise; a famous customer would need
per-key locking on miss. Named as a TODO.

### D5 - Failure mapping: 503 with `retryable`, not 500

Dead Postgres is a *runtime condition*, so the app boots without it (Hikari
`initialization-fail-timeout=-1` in that mode, dialect pinned, script init skipped
per-context) and maps connection/tx/timeout failures to `503 datastore-unavailable`
with a `retryable: true` hint. 409 is reserved for real business conflicts; 404
covers unknown ids AND unknown routes (a catch-all `Exception` handler must not
swallow MVC's own 404s into 500s).

### D6 - Bean validation at the edge, business rules in the service

Request-shape rules (`@NotBlank`, `@Size`, `@Pattern`, `@NotOffensive`) live on the
request record; rules that need the database (quota) live in the service. The
`@NotOffensive` check is a bean-validation constraint so the declarative error
surface is uniform (field issues array), but its dependency is injected via Spring's
constraint-validator factory.

### D7 - Test infrastructure: singleton containers, isolated outage contexts

Happy-path suites share one Postgres+Redis pair (static singletons wired via
`@DynamicPropertySource`), keeping ~10 suites to two container boots. Outage
suites deliberately do NOT extend that base: `@DynamicPropertySource` outranks
test-class properties, so a dead-endpoint context must own its wiring to keep the
dead port authoritative. `@TestConfiguration` (nested) adds slice beans; `@Import`
of a configuration class would have *replaced* the slice's scanned controllers -
an encoding of a real Spring pitfall.

### D8 - Version column with no mutation endpoint

`version` + `@Version` cost one column now; retrofitting optimistic locking onto a
live table later costs a migration plus backfill discipline. Cheap insurance.

## TODOs left in the source (deliberate)

| TODO | Why left |
|---|---|
| `TODO(moderation)` in `OffensiveWordFilter` | production = moderation service + curated corpus + human review |
| `TODO(infra)` schema via Flyway/Liquibase | migrations beat `schema.sql` in real repos; out of exercise scope |
| `TODO(caching)` stampede protection | only relevant at high miss-rate hot keys |
| idempotency on POST (see below) | real gap, real design; wanted to keep the required surface honest |

## Future work the next increment should add

1. **Idempotent create.** Retries (mobile network, gateway timeout) can currently
   create duplicate accounts - customer-visible damage. Design: mandatory
   `Idempotency-Key` header, table-backed (not cache-backed) `(caller_id, key)`
   unique index + stored response replay, `409` while in-flight, `422` on key reuse
   with a different payload, and an expiry column so a stuck in-flight key cannot
   wedge a customer forever.
2. **Customer entity + FK**, replacing name-as-identity (A1) and switching the
   quota lock to a row lock on the customer row.
3. **Mutation endpoints** (`PATCH /nickname`, close account) with explicit cache
   eviction, optimistic-lock retry policy, and an event outbox for downstream
   consumers.
4. **Money movement**, if asked: append-only postings ledger + derived balance,
   integer minor units, idempotent transfers, reconciliation check as a test and a
   metric. Not built because the spec does not ask for it.
5. **Observability**: structured logs with correlation ids, request metrics,
   a reconciliation/consistency probe.
6. **Pagination/list endpoints** with keyset pagination when "get all accounts for
   a customer" arrives.
