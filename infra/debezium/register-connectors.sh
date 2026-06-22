#!/usr/bin/env bash
# Registers all three Debezium outbox connectors.
# Run after `docker compose up` once the stack is healthy.
# Requires: curl, jq

set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

wait_for_connect() {
  echo "Waiting for Debezium Connect to be ready..."
  until curl -sf "${CONNECT_URL}/connectors" > /dev/null; do
    sleep 2
  done
  echo "Debezium Connect is ready."
}

register() {
  local name="$1"
  local file="$2"
  echo "Registering connector: ${name}"
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${CONNECT_URL}/connectors" \
    -H "Content-Type: application/json" \
    -d @"${file}")
  if [[ "${http_code}" == "201" ]]; then
    echo "  Created: ${name}"
  elif [[ "${http_code}" == "409" ]]; then
    echo "  Already exists (skipping): ${name}"
  else
    echo "  ERROR: unexpected HTTP ${http_code} registering ${name}" >&2
    exit 1
  fi
}

wait_for_connect
register "declare-outbox-connector"   "${SCRIPT_DIR}/connector-declare.json"
register "validate-outbox-connector"  "${SCRIPT_DIR}/connector-validate.json"
register "risk-outbox-connector"      "${SCRIPT_DIR}/connector-risk.json"

echo ""
echo "Registered connectors:"
curl -s "${CONNECT_URL}/connectors" | jq .
