#!/usr/bin/env bash
set -euo pipefail

XMR_FLOW_ROOT=$(git rev-parse --show-toplevel)
XMR_FLOW_DB="wallet_xmr_it_$$"
XMR_FLOW_DB_USER=$(id -un)

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  "${XMR_FLOW_ROOT}/scripts/regtest/monero-regtest.sh" stop >/dev/null 2>&1 || true
  dropdb -h 127.0.0.1 --if-exists "${XMR_FLOW_DB}" >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT INT TERM

for command in createdb docker dropdb git mvn psql; do
  command -v "${command}" >/dev/null || {
    printf 'missing required command: %s\n' "${command}" >&2
    exit 1
  }
done

docker info >/dev/null
"${XMR_FLOW_ROOT}/scripts/regtest/monero-regtest.sh" reset
createdb -h 127.0.0.1 "${XMR_FLOW_DB}"
psql -h 127.0.0.1 -d "${XMR_FLOW_DB}" -q -v ON_ERROR_STOP=1 \
  -f "${XMR_FLOW_ROOT}/docs/db/surprising-wallet-init-pgsql.sql" >/dev/null

MONERO_REGTEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/${XMR_FLOW_DB}" \
MONERO_REGTEST_DB_USER="${XMR_FLOW_DB_USER}" \
MONERO_REGTEST_DB_PASSWORD= \
mvn -f "${XMR_FLOW_ROOT}/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=MoneroRegtestFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmonero.regtest.enabled=true test

negative_balances=$(psql -h 127.0.0.1 -d "${XMR_FLOW_DB}" -Atqc \
  "select count(*) from ledger_balance where chain='XMR' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)")
if [[ "${negative_balances}" != 0 ]]; then
  printf 'XMR post-test audit found %s negative balances\n' "${negative_balances}" >&2
  exit 1
fi

printf 'XMR PASS regtest deposit, withdrawal, collection, idempotency, and balance audit\n'
