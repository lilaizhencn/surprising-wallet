#!/usr/bin/env bash
set -euo pipefail

XMR_FLOW_ROOT=$(git rev-parse --show-toplevel)
source "$XMR_FLOW_ROOT/scripts/regtest/local-postgres.sh"
XMR_FLOW_DB="surprising_wallet_test_xmr_$$"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  "${XMR_FLOW_ROOT}/scripts/regtest/monero-regtest.sh" stop >/dev/null 2>&1 || true
  local_pg_drop "$XMR_FLOW_DB" >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT INT TERM

for command in docker git mvn; do
  command -v "${command}" >/dev/null || {
    printf 'missing required command: %s\n' "${command}" >&2
    exit 1
  }
done

docker info >/dev/null
"${XMR_FLOW_ROOT}/scripts/regtest/monero-regtest.sh" reset
local_pg_require
local_pg_create "$XMR_FLOW_DB"
XMR_FLOW_DB_URL=$(local_pg_jdbc_url "$XMR_FLOW_DB")
local_pg_psql "$XMR_FLOW_DB" -q -v ON_ERROR_STOP=1 \
  -f "${XMR_FLOW_ROOT}/docs/db/surprising-wallet-init-pgsql.sql" >/dev/null

MONERO_REGTEST_DB_URL="$XMR_FLOW_DB_URL" \
MONERO_REGTEST_DB_USER="$REGTEST_PG_USER" \
MONERO_REGTEST_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
mvn -f "${XMR_FLOW_ROOT}/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=MoneroRegtestFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmonero.regtest.enabled=true test

negative_balances=$(local_pg_psql "$XMR_FLOW_DB" -Atqc \
  "select count(*) from ledger_balance where chain='XMR' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)")
if [[ "${negative_balances}" != 0 ]]; then
  printf 'XMR post-test audit found %s negative balances\n' "${negative_balances}" >&2
  exit 1
fi

printf 'XMR PASS regtest deposit, withdrawal, collection, idempotency, and balance audit\n'
