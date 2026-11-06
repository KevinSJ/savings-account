#!/usr/bin/env bash
# Standalone runner: executes hurl/api.hurl inside the official hurl image
# against a running stack (docker compose up -d -run "everything").
#
# Usage:
#   ./hurl/run.sh                       # auto-detects the compose network, hits 'app'
#   NETWORK=foo_default HOST=api ./hurl/run.sh
#
# Env:
#   HURL_IMAGE  hurl container image     (default ghcr.io/orange-opensource/hurl:8.1.0)
#   NETWORK     docker network name      (default <compose-project>_default)
#   HOST        api hostname on that net (default: app)
#   RUN_SUFFIX  unique suffix per run    (default: $RANDOM) - digits are mapped to
#               letters because customerName must not contain digits; the mapping
#               preserves uniqueness (0-9 -> a-j)
set -euo pipefail

cd "$(dirname "$0")/.."

HURL_IMAGE="${HURL_IMAGE:-ghcr.io/orange-opensource/hurl:8.1.0}"
HOST="${HOST:-app}"
RUN_SUFFIX="${RUN_SUFFIX:-$RANDOM}"
# customerName rejects digits; map 0-9 -> a-j so the suffix stays unique AND valid.
SUFFIX_TEXT="$(printf '%s' "$RUN_SUFFIX" | tr '0-9' 'abcdefghij')"
NETWORK="${NETWORK:-}"

if [[ -z "$NETWORK" ]]; then
  PROJECT="$(docker compose config --format json | python3 -c 'import json,sys;print(json.load(sys.stdin)["name"])' 2>/dev/null || basename "$PWD")"
  NETWORK="${PROJECT}_default"
fi

echo "hurl -> network=$NETWORK host=$HOST suffix=$SUFFIX_TEXT"
# NOTE: the official image's ENTRYPOINT is /usr/bin/hurl, so flags and the file
# are passed directly - adding the word "hurl" again would register a second file.
exec docker run --rm \
  --network "$NETWORK" \
  -v "$PWD/hurl:/hurl:ro" \
  "$HURL_IMAGE" \
    --test \
    --variable "host=$HOST" \
    --variable "suffix=$SUFFIX_TEXT" \
    /hurl/api.hurl
