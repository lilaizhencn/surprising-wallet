#!/usr/bin/env bash
set -euo pipefail
umask 077
SEEDLESS_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set the built native signer path}"
: "${MPC_WALLET_JAR:?set the clean packaged wallet-api JAR path}"
: "${MPC_NEAR_TEST_FIXTURE:?set the preserved official local NEAR fixture}"
source "$SEEDLESS_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
SEEDLESS_DATABASE="surprising_wallet_test_mpc_startup_$$"
local_pg_create "$SEEDLESS_DATABASE"
trap 'local_pg_drop "$SEEDLESS_DATABASE" >/dev/null 2>&1 || true' EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$SEEDLESS_DATABASE")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
python3 "$SEEDLESS_ROOT/resources/infra/mpc-signer/tests/seedless_startup_test.py" "$MPC_SIGNER_BIN" "$MPC_WALLET_JAR" "$MPC_NEAR_TEST_FIXTURE"
