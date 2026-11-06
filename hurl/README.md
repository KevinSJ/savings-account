# Hurl acceptance suite

`api.hurl` exercises the deployed API end to end over real HTTP, asserting the
contract documented in `openapi/savings-accounts-api.yaml`: health, create
(201 + Location), fetch by id and by account number (incl. `Cache-Control`),
validation 400s (missing name, whitespace-only nickname, offensive nickname,
malformed JSON), the 5-account quota (five 201s then one 409 with
`maxAccounts: 5`), 404/400 error types for unknown/invalid ids, malformed
account numbers, and unknown-route 404.

Run inside docker only - no host tooling required:

```bash
# after `docker compose up -d` (the app is served by the compose network):
./hurl/run.sh

# or, through compose (skip --no-deps on a cold machine):
docker compose --profile tests run --rm --no-deps hurl

# or, by hand:
docker run --rm --network <compose-network> -v "$PWD/hurl:/hurl:ro" \
  ghcr.io/orange-opensource/hurl:8.1.0 \
  --test --variable host=app --variable suffix=xyz /hurl/api.hurl
```

Notes:

- The image's entrypoint **is** hurl itself; flags and the .hurl file are passed
  as plain arguments. Do not add the word `hurl` to the command.
- Customers get a per-run suffix (`0-9 -> a-j`) so the suite is re-runnable
  against a warm database without tripping the 5-account quota.
- Not covered here (they need engineered conditions, see Gradle ITs instead):
  advisory-lock contention 503s (need a foreign lock holder) and datastore
  outage 503s (need dead dependency ports).
- Env knobs for `run.sh`: `HURL_IMAGE`, `NETWORK`, `HOST`, `RUN_SUFFIX`.
