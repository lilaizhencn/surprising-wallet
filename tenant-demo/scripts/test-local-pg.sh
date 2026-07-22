#!/usr/bin/env bash
set -euo pipefail

DEMO_TEST_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source "$DEMO_TEST_ROOT/scripts/regtest/local-postgres.sh"

DEMO_TEST_DB="surprising_wallet_test_tenant_demo_$$"

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  local_pg_drop "$DEMO_TEST_DB" >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

local_pg_require
local_pg_create "$DEMO_TEST_DB"

TENANT_DEMO_PG_HOST="$REGTEST_PG_HOST" \
TENANT_DEMO_PG_PORT="$REGTEST_PG_PORT" \
TENANT_DEMO_PG_DATABASE="$DEMO_TEST_DB" \
TENANT_DEMO_PG_USER="$REGTEST_PG_USER" \
TENANT_DEMO_PG_PASSWORD="$REGTEST_PG_PASSWORD" \
TENANT_DEMO_PG_SCHEMA=public \
npm run test:node
