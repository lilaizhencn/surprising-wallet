#!/usr/bin/env bash
set -euo pipefail

CUSTODY_DB_ROOT=$(git rev-parse --show-toplevel)
source "$CUSTODY_DB_ROOT/scripts/regtest/local-postgres.sh"

CUSTODY_DB_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-custody-db-tests.XXXXXX)
CUSTODY_DB_NAME="surprising_wallet_test_custody_$$"
CUSTODY_DB_TESTS=${CUSTODY_DB_TESTS:-CustodyDepositProjectionIntegrationTest,CustodyOperationsIntegrationTest,CustodyTenantIsolationIntegrationTest,Evm7702ProductionFlowIntegrationTest}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$CUSTODY_DB_NAME" >/dev/null 2>&1 || true
  if [[ "$CUSTODY_DB_BUILD_ROOT" == /tmp/surprising-wallet-custody-db-tests.* ]] \
      && [[ -d "$CUSTODY_DB_BUILD_ROOT" ]]; then
    trash "$CUSTODY_DB_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in git mvn rsync trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require
local_pg_create "$CUSTODY_DB_NAME"

rsync -a \
  --exclude=.git \
  --exclude=.codegraph \
  --exclude=target \
  --exclude=node_modules \
  --exclude=logs \
  "$CUSTODY_DB_ROOT/" "$CUSTODY_DB_BUILD_ROOT/"

SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$CUSTODY_DB_NAME")" \
SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER" \
SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
mvn -q -f "$CUSTODY_DB_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest="$CUSTODY_DB_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "$@" \
  test

printf 'Custody DB integration tests passed on local PostgreSQL 18\n'
