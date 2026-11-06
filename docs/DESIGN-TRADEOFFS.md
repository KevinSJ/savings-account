# Design trade-offs and assumptions

The requirements leave real gaps. Each gap below states the assumption made, the
alternative(s) rejected, and why. Nothing here is load-bearing cleverness: every
choice can be swapped without touching the API contract.

## Stated assumptions (where the spec was insufficient)

| # | Assumption | Rationale |
|---|---|---|
| A1 | A "customer" is identified by their name, canonicalised (NFKC fold + whitespace-run collapse + strip) and matched case-insensitively | The spec defines no customer entity. Name-as-identity is intentionally crude; a real system would carry a `customer_id` FK. Post-review hardening: whitespace variants and unicode-equivalent spellings collapse to one identity (an earlier revision admitted "Alice  Tester" as a distinct customer - caught in review, covered by `AccountFlowIT.quotaSurvivesWhitespaceVariants`). |
| A2 | The 5-account limit is per customer across the whole system, not per session/request | "customer cannot create more than 5 accounts" reads as a policy about the customer, not about transport sessions. |
| A3 | Nickname checks apply only to the nickname, not the customer name | Spec says "ensure nickname not offensive". Applying the same screen to names is a one-line extension; name matching is riskier (false positives on real names). |
| A4 | A blank (`""`/whitespace) nickname is invalid, not "absent" | Absence is expressed as JSON `null` / omitted field. Enforced by stripping the nickname *before* bean validation, so a whitespace-only body fails `@Size(min=5)` with 400 (the first revision silently turned it into `null` + 201 - caught in review). Stored nicknames always honour the 5-30 contract. |
| A5 | No money/balance/transaction operations | The spec asks only for create + read. Building a ledger (postings, balance, interest) would triple the surface without a requirement - see "future work". |
| A6 | Account number format is internal-only (12 digits + Luhn) | No jurisdiction/IBAN scheme was specified. The generator is an interface; a country-specific scheme slots in behind it. |
| A7 | HTTP is the only access path; no authn/authz layer | No requirement mentions security actors. A real deployment would front this with the bank's identity fabric. Honest consequence (added post-review): with sequential ids and no auth, an attacker can walk id space 1..N and harvest customer names + account numbers. Mitigations shipped: a `by-number` lookup key (random-looking, Luhn-checked) and `Cache-Control: no-store, private` on reads; the structural fix (opaque id/UUIDv7 keys, authn) belongs to a real identity layer and is stated here rather than pretended away. |

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

**Residual risk:** the key is the (hashed) canonical name; two names hashing
identically would serialise unnecessarily - harmless (locking "too much" only costs
throughput). Case handling: lock key and count both fold with `upper()` over the
canonical name (proven by the mixed-case race in `ConcurrentAccountLimitIT`).

**Bounded wait (post-review).** `pg_advisory_xact_lock` would originally wait
forever, making the 503-contention handler unreachable dead code. Every create
transaction now sets `lock_timeout = 2s` (`set_config(..., is_local := true)`);
an expired wait raises SQLState 55P03, which is classified explicitly (Spring's
default translation misses it for PostgreSQL) into `LockContentionException`
-> 503 `contention`, retryable. Proof: `LockContentionIT` holds the lock from a
foreign session and asserts the bounded 503 plus recovery after release.

**Known characteristic (accepted):** the lock is held for the whole transaction,
including the count and the account-number probe; a hot customer queues against
Hikari's bounded pool. The bound plus the small transaction keeps the worst case
short; a customer-row pessimistic lock (future work #2) behaves the same way.

### D2 - Account numbers: random + Luhn, not a sequence

**Chosen:** 11 secure-random digits + Luhn check digit (12 total). Uniqueness probed
against the table (max 5 attempts), with the DB unique constraint as the last resort.

- vs. DB sequence: monotonic numbers leak business volume and invite enumeration;
  a fixed-format check digit also catches transposition typos before any lookup.
- vs. random UUID: 12 numeric digits are what customers can read over a phone call.
- Cost: collision retries. With a 10^11 space, expected attempts ~ 1.0; the retry cap
  converts pathology into a retryable 503 (`AccountNumberAllocationException`).

### D3 - Nickname offensiveness: local filter now, moderation service eventually

**Chosen:** a classpath blocklist + three passes: exact tokens after NFKC folding;
a letter-skeleton substring pass (>=5-letter terms) for glued obfuscation like
`s-t-u-p-i-d`; and a leet-pattern pass (only when the input carries digits or
`@$!|+`) where each >=4-letter banned word becomes a per-character ambiguity-class
regex, so `h311`, `he11`, `d4mn`, `st0pid`, `bl00dy` are caught. The first revision
had a single-target fold (`1->i`) that missed exactly those vectors and a test that
masked the gap ("blo0dy h311" passed via `blo0dy` alone) - caught in review.
Digit-free text keeps the strict passes only, preserving the Scunthorpe guard.

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

- **Why cache the entity:** all-scalar, no lazy relations, read-only usage - safe,
  with one caveat worth naming: the round-trip relies on reflective field access
  because the entity deliberately has no public setters, so a malformed cache entry
  could bypass the constructor's invariants. The `version` field is exposed via a
  getter precisely so the cached snapshot carries it. If the entity ever grows
  relations or lazy state, move the cached shape to an immutable snapshot record
  (documented seam).
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

Post-review refinements: `JpaSystemException` was removed from the transient
bucket (it wraps deterministic persistence bugs - telling clients to retry those
manufactures retry storms); a lost account-number probe-insert race maps to
retryable 503 `allocation-collision` instead of blaming the caller with 409;
integrity-violation logs redact digit runs so constraint keys (account numbers)
stay out of logs.

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
   quota lock to a row lock on the customer row; with it, opaque (UUIDv7-style)
   identifiers for URLs replace sequential ids, closing the enumeration window.
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
