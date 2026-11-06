# Security recommendations (bank-grade lens)

Kevin's framing for this exercise: "assume this is a bank-grade API - even as
practice, security has to be considered." This note separates what the code
already enforces from what a production rollout must add, with the threat each
item answers. Order is by severity-weighted payoff.

## Already implemented in this repository

| Control | Threat addressed | Where |
|---|---|---|
| Opaque external `id` (UUIDv4), sequential id never on the wire | Enumeration / brute-force of accounts | D10 in DESIGN-TRADEOFFS.md |
| Minimal create response (account number only; id only via `Location`) | Response over-disclosure; id proliferation into client logs | requirement 3, enforced by tests |
| RFC 9457 errors with fixed type URIs, no stack traces/ internals in `detail` | Information disclosure | GlobalExceptionHandler |
| Advisory-lock quota + bounded `lock_timeout` | Race-based policy violation, connection-pool pile-up | AccountService |
| Offensive-term screening on nicknames (+ unicode/leet normalisation) | Abuse/UGC hygiene | OffensiveWordFilter |
| `Cache-Control: no-store, private` on reads | Intermediary retention of PII | AccountController |
| Digit-run redaction in integrity logs | PII leakage into logs | GlobalExceptionHandler |
| Bounded Hikari pool, 3s acquisition, lazy Redis | Resource exhaustion cascades | application.yml |
| Retryable-only 503 semantics | Clients amplifying outages with blind retries | GlobalExceptionHandler |

## Production additions, ranked

1. **Authentication + authorization** (blocks: everything else is moot without
   it). OAuth2 client-credentials for service callers; customer-scoped tokens so
   a caller can only read accounts belonging to them (requires a customer/holder
   relation). This is also the answer to "who may call GET /accounts/{id} at
   all".
2. **Idempotency on create** (blocks: duplicate accounts from network/gateway
   retries - customer-visible damage, not a 500). Mandatory `Idempotency-Key`
   header, table-backed `(caller_id, key)` unique index, replay of the original
   response, `409` while in-flight, `422` on payload reuse, expiry on stuck
   keys. Designed but not built - see DESIGN-TRADEOFFS.md future work.
3. **Rate limiting / anti-automation** (blocks: slow brute force of the
   by-number space, ~5x10^10 valid candidates; abuse of read endpoints).
   Per-caller and per-IP throttles, exponential lockout on repeated 404 probes,
   alerting on anomalous lookup velocity. The UUID lookup itself is practically
   unguessable (122 bits); the account-number endpoint is the residual target.
4. **Audit trail** (answers: "who created/read what, when" after the fact).
   Append-only audit events for create/read with correlation ids, PII-redacted
   (the current controller log already redacts names; extend to structured
   audit storage with tamper-evidence).
5. **Transport + headers** (blocks: interception/downgrade). TLS 1.2+ only,
   HSTS at the edge, `X-Content-Type-Options: nosniff`; the exercise serves
   plain HTTP for localhost ergonomics.
6. **Data protection at rest** (blocks: DB/media theft). Column or volume
   encryption for `customer_name`, encrypted backups, managed key rotation.
   Note: nothing in this schema is a secret today - the rotation discipline
   matters the moment any secret-backed control (e.g. HMAC ids) is adopted;
   UUID external ids are deliberately rotation-immune.
7. **Schema migrations** (blocks: drift; a live demonstration already happened:
   a stale volume served 500s because `IF NOT EXISTS` never adds columns).
   Flyway/Liquibase owning `savings_accounts`, forward-only migrations, CI
   migration-drift check.
8. **Input-policy extensions** (blocks:名称-adjacent abuse). The customer-name
   charset pattern already blocks digits/control chars; consider the same
   normalization/screening pipeline as nicknames (homoglyph folding) if names
   become user-searchable.
9. **Edge hardening** (blocks: volumetric/protocol attacks). WAF/DDoS scrubbing,
   request-size caps, connection limits - outside the app's scope but part of
   the bank-grade picture.
10. **Operational security testing** (verifies: all of the above). The hurl
    suite belongs in CI as a contract gate; chaos drills for the datastore-outage
    path already exist as Testcontainers suites; add authz-negative tests once
    authorization exists.

## Deliberate non-goals for the exercise (stated, not hidden)

- No authentication was added "for show": a half-configured security filter
  gives the appearance of protection without the substance, and the exercise
  defines no security actors.
- Money movement, statements, interest: out of the specified scope; the ledger
  design notes in DESIGN-TRADEOFFS.md future work cover how they would be
  added without weakening the above.
