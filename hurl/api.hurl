# API acceptance suite, executable standalone inside the official hurl image:
#   docker run --rm --network <compose-net> -v "$PWD/hurl:/hurl:ro" \
#     ghcr.io/orange-opensource/hurl:8.1.0 \
#     hurl --test --variable host=app --variable suffix=$RANDOM /hurl/api.hurl
# or simply: ./hurl/run.sh   (after `docker compose up -d`)
#
# Every assertion mirrors the contract documented in openapi/savings-accounts-api.yaml.
# Customers are suffixed with {{suffix}} so the suite can re-run against a warm
# database without tripping the 5-account quota on its own leftovers.
#
# Not covered here (they need non-standard setups, see the Gradle integration
# suites instead): advisory-lock contention (needs a lock holder) and datastore
# outage 503s (need dead dependency ports).

# --- health ------------------------------------------------------------------

GET http://{{host}}:8080/actuator/health
HTTP 200
[Asserts]
jsonpath "$.status" == "UP"


# --- create: happy path ------------------------------------------------------

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Hurl Harold-{{suffix}}",
  "nickname": "hurl nest"
}

HTTP 201
# Requirement: the create BODY must not carry the id; it MAY carry the account
# number. The opaque generated id travels exactly once, in the Location header.
[Captures]
accountId: header "Location"
accountNumber: jsonpath "$.accountNumber"
[Asserts]
header "Location" matches "^/api/v1/accounts/[0-9a-f-]{36}$"
jsonpath "$.accountNumber" matches /^\d{12}$/
# (create-body minimality - no id field - is pinned by the JVM web-slice test,
#  which asserts $.id doesNotExist directly; hurl 8 has no body-query predicate)

# --- fetch by opaque id (full data model) --------------------------------------

GET http://{{host}}:8080{{accountId}}
HTTP 200
[Asserts]
jsonpath "$.id" matches /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
jsonpath "$.accountNumber" == "{{accountNumber}}"
jsonpath "$.customerName" exists
header "Cache-Control" == "no-store, private"


# --- fetch by public account number -----------------------------------------

GET http://{{host}}:8080/api/v1/accounts/by-number/{{accountNumber}}
HTTP 200
[Asserts]
jsonpath "$.id" matches /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
header "Cache-Control" == "no-store, private"


# --- create: mandatory name --------------------------------------------------

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{}

HTTP 400
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/validation-failed"
jsonpath "$.issues[0].field" == "customerName"


# --- create: whitespace-only nickname is invalid, not absent (A4) ------------

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Hurl Harold-{{suffix}}",
  "nickname": "     "
}

HTTP 400
[Asserts]
jsonpath "$.issues[0].field" == "nickname"
jsonpath "$.issues[0].message" contains "between 5 and 30"


# --- create: offensive nickname rejected -------------------------------------

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Hurl Harold-{{suffix}}",
  "nickname": "my stupid jar"
}

HTTP 400
[Asserts]
jsonpath "$.issues[0].field" == "nickname"
jsonpath "$.issues[0].message" contains "offensive"


# --- create: malformed JSON body ----------------------------------------------
# Triple-backtick body = raw bytes; hurl's JSON-body parser would otherwise
# reject the deliberate syntax error locally instead of testing our handler.

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

```
{oops
```

HTTP 400
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/malformed-body"


# --- quota: five slots then 409 ----------------------------------------------

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "slot one"
}

HTTP 201

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "slot two"
}

HTTP 201

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "slot three"
}

HTTP 201

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "slot four"
}

HTTP 201

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "slot five"
}

HTTP 201

POST http://{{host}}:8080/api/v1/accounts
Content-Type: application/json

{
  "customerName": "Quota Quinn-{{suffix}}",
  "nickname": "one too many"
}

HTTP 409
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/account-limit-reached"
jsonpath "$.maxAccounts" == 5


# --- not found / invalid ids ---------------------------------------------------

GET http://{{host}}:8080/api/v1/accounts/00000000-0000-4a2b-9c3d-5e6f70899a0b
HTTP 404
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/account-not-found"

# non-UUID probes fail conversion before any datastore access - the historical
# sequential id space ('1', '2', ...) is no longer a lookup surface at all
GET http://{{host}}:8080/api/v1/accounts/not-a-uuid
HTTP 400
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/invalid-parameter"

GET http://{{host}}:8080/api/v1/accounts/1
HTTP 400
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/invalid-parameter"


# --- account-number format is validated before the datastore -------------------

# flip the check digit of a Luhn-valid number -> malformed
GET http://{{host}}:8080/api/v1/accounts/by-number/325748979053
HTTP 400
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/invalid-request"


# --- unknown route is a 404, not a 500 ------------------------------------------

GET http://{{host}}:8080/api/v1/unknown
HTTP 404
[Asserts]
jsonpath "$.type" == "https://api.takehome.co.nz/errors/route-not-found"
