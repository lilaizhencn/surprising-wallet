#!/usr/bin/env bash
set -euo pipefail

LOAD_ROOT=$(git rev-parse --show-toplevel)
source "$LOAD_ROOT/scripts/regtest/local-postgres.sh"
LOAD_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-multichain-load.XXXXXX)
LOAD_DB="surprising_wallet_test_multichain_load_$$"
LOAD_USERS=${LOAD_USERS:-1000}
LOAD_CONCURRENCY=${LOAD_CONCURRENCY:-32}
LOAD_WEBHOOK_WORKERS=${LOAD_WEBHOOK_WORKERS:-12}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$LOAD_DB" >/dev/null 2>&1 || true
  if [[ "$LOAD_BUILD_ROOT" == /tmp/surprising-wallet-multichain-load.* ]]; then
    rm -rf "$LOAD_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in git mvn rsync; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

local_pg_require
local_pg_create "$LOAD_DB"
LOAD_DB_URL=$(local_pg_jdbc_url "$LOAD_DB")

rsync -a \
  --exclude=.git \
  --exclude=.codegraph \
  --exclude=target \
  --exclude=node_modules \
  --exclude=/resources/infra/evm-fork/artifacts \
  --exclude=logs \
  "$LOAD_ROOT/" "$LOAD_BUILD_ROOT/"

SW_TEST_CUSTODY_DB_URL="$LOAD_DB_URL" \
SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER" \
SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
mvn -f "$LOAD_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=CustodyMultiChainLoadIntegrationTest \
  -Dcustody.load.enabled=true \
  -Dcustody.load.users="$LOAD_USERS" \
  -Dcustody.load.concurrency="$LOAD_CONCURRENCY" \
  -Dcustody.load.webhookWorkers="$LOAD_WEBHOOK_WORKERS" \
  test

report="$LOAD_BUILD_ROOT/backendservices/wallet-parent/wallet-server/target/multi-chain-load-report.properties"
if [[ ! -s "$report" ]]; then
  printf 'multi-chain load report was not generated: %s\n' "$report" >&2
  exit 1
fi
printf 'MULTI-CHAIN LOAD PASS\n'
sed -n '1,40p' "$report"
