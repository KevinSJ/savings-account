#!/usr/bin/env bash
#
# Regenerates openapi/savings-accounts-api.yaml from the running application.
#
# The contract is derived from the springdoc annotations on the controllers and
# DTOs, so this script is the only sanctioned way to change it: never hand-edit
# the YAML.
#
# Normal use:   ./gradlew generateOpenApi
# Standalone:   ./scripts/generate-openapi.sh          (builds the jar first)
#               SKIP_BUILD=1 ./scripts/generate-openapi.sh
#
# Ports are deliberately non-default so a developer stack already listening on
# 5432/6379 does not get reused or disturbed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OUT="openapi/savings-accounts-api.yaml"
JAR="build/libs/app.jar"
LOG="build/openapi-run.log"
COMPOSE_PROJECT="savings-openapi"

PG_PORT="${OPENAPI_PG_PORT:-55432}"
REDIS_PORT="${OPENAPI_REDIS_PORT:-56379}"
APP_PORT="${OPENAPI_APP_PORT:-18080}"
STARTUP_TIMEOUT="${OPENAPI_STARTUP_TIMEOUT:-90}"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo "==> building boot jar"
    ./gradlew bootJar -x test --quiet
fi
[[ -f "$JAR" ]] || { echo "ERROR: $JAR not found; run ./gradlew bootJar" >&2; exit 1; }

APP_PID=""
cleanup() {
    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    PG_HOST_PORT="$PG_PORT" REDIS_HOST_PORT="$REDIS_PORT" \
        docker compose -p "$COMPOSE_PROJECT" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> starting throwaway postgres (:${PG_PORT}) and redis (:${REDIS_PORT})"
PG_HOST_PORT="$PG_PORT" REDIS_HOST_PORT="$REDIS_PORT" \
    docker compose -p "$COMPOSE_PROJECT" up -d --wait postgres redis

echo "==> starting application on :${APP_PORT}"
mkdir -p build
DB_URL="jdbc:postgresql://localhost:${PG_PORT}/savings" \
REDIS_HOST="localhost" \
REDIS_PORT="$REDIS_PORT" \
    java -jar "$JAR" \
        --server.port="$APP_PORT" \
        --springdoc.api-docs.enabled=true \
        >"$LOG" 2>&1 &
APP_PID=$!

TMP="$(mktemp)"
for _ in $(seq 1 "$STARTUP_TIMEOUT"); do
    if curl -fsS "http://localhost:${APP_PORT}/v3/api-docs.yaml" -o "$TMP" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "ERROR: application exited during startup; see $LOG" >&2
        tail -30 "$LOG" >&2
        exit 1
    fi
    sleep 1
done

if [[ ! -s "$TMP" ]]; then
    echo "ERROR: no document retrieved from /v3/api-docs.yaml after ${STARTUP_TIMEOUT}s" >&2
    tail -30 "$LOG" >&2
    exit 1
fi

{
    echo "# GENERATED FILE - do not edit by hand."
    echo "# Source: springdoc annotations on the controllers and DTOs."
    echo "# Regenerate with: ./gradlew generateOpenApi"
    cat "$TMP"
} >"$OUT.tmp"
mv "$OUT.tmp" "$OUT"
rm -f "$TMP"

echo "==> wrote $OUT ($(wc -l <"$OUT") lines)"
