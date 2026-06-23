#!/usr/bin/env bash
# Verify that each service's Docker image is under 100 MB.
# Run from the repo root after building: docker compose -f infra/docker-compose.yml build
set -euo pipefail

MAX_MB=100
FAILED=0
PROJECT=$(basename "$PWD")

check() {
  local svc="$1"
  local image="${PROJECT}-${svc}"

  if ! docker image inspect "$image" &>/dev/null; then
    echo "MISSING  $image  (not built yet)"
    FAILED=1
    return
  fi

  local bytes mb
  bytes=$(docker image inspect "$image" --format '{{.Size}}')
  mb=$(( bytes / 1048576 ))

  if [ "$mb" -lt "$MAX_MB" ]; then
    printf "OK       %-40s %d MB\n" "$image" "$mb"
  else
    printf "FAIL     %-40s %d MB  (limit %d MB)\n" "$image" "$mb" "$MAX_MB"
    FAILED=1
  fi
}

check svc-declare
check svc-validate
check svc-risk
check svc-audit

if [ "$FAILED" -ne 0 ]; then
  echo ""
  echo "Image size check FAILED"
  exit 1
fi

echo ""
echo "All images within ${MAX_MB} MB limit"
