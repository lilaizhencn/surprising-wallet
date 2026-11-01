#!/usr/bin/env bash
set -euo pipefail
umask 077
APPROVAL_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$APPROVAL_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
APPROVAL_MEMBER_DB="surprising_wallet_test_approval_members_$$"
APPROVAL_AUTH_DB="surprising_wallet_test_approval_authority_$$"
cleanup() {
  local_pg_drop "$APPROVAL_MEMBER_DB" || true
  local_pg_drop "$APPROVAL_AUTH_DB" || true
}
trap cleanup EXIT
local_pg_create "$APPROVAL_MEMBER_DB"
local_pg_create "$APPROVAL_AUTH_DB"
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$APPROVAL_MEMBER_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
export SW_TEST_APPROVAL_DB_URL="$(local_pg_jdbc_url "$APPROVAL_AUTH_DB")"
export SW_TEST_APPROVAL_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_APPROVAL_DB_PASSWORD="$REGTEST_PG_PASSWORD"
export SW_TEST_PGMQ=true
export SW_TEST_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$APPROVAL_ROOT/pom.xml" -pl wallet-api,wallet-approval -am "$@" package
