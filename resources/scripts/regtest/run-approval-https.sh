#!/usr/bin/env bash
set -euo pipefail
umask 077
APPROVAL_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$APPROVAL_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
APPROVAL_TEST_DB="surprising_wallet_test_approval_https_$$"
APPROVAL_ROLE_SUFFIX=$(python3 -c 'import secrets; print(secrets.token_hex(6))')
APPROVAL_WALLET_OWNER="sw_test_wo_${APPROVAL_ROLE_SUFFIX}"
APPROVAL_AUTH_OWNER="sw_test_ao_${APPROVAL_ROLE_SUFFIX}"
export APPROVAL_WALLET_RUNTIME="sw_test_wr_${APPROVAL_ROLE_SUFFIX}"
export APPROVAL_AUTH_RUNTIME="sw_test_ar_${APPROVAL_ROLE_SUFFIX}"
export APPROVAL_AUTH_DB_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
export APPROVAL_WALLET_DB_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
cleanup() {
  local_pg_drop "$APPROVAL_TEST_DB" || true
  for role in "$APPROVAL_WALLET_RUNTIME" "$APPROVAL_AUTH_RUNTIME" "$APPROVAL_WALLET_OWNER" "$APPROVAL_AUTH_OWNER"; do
    local_pg_psql "$REGTEST_PG_ADMIN_DB" -v ON_ERROR_STOP=1 -c "DROP ROLE IF EXISTS \"$role\"" >/dev/null || true
  done
}
trap cleanup EXIT
local_pg_create "$APPROVAL_TEST_DB"
local_pg_psql "$APPROVAL_TEST_DB" -v ON_ERROR_STOP=1 -f "$APPROVAL_ROOT/resources/docs/db/surprising-wallet-init-pgsql.sql" > /tmp/surprising-wallet-approval-https-baseline.log 2>&1
local_pg_psql "$APPROVAL_TEST_DB" -v wallet_owner="$APPROVAL_WALLET_OWNER" -v approval_owner="$APPROVAL_AUTH_OWNER" \
  -v wallet_runtime="$APPROVAL_WALLET_RUNTIME" -v approval_runtime="$APPROVAL_AUTH_RUNTIME" \
  -f "$APPROVAL_ROOT/resources/infra/approval/database-roles.sql" > /tmp/surprising-wallet-approval-https-roles.log 2>&1
# Generated test credentials travel only over stdin/environment; never through command-line SQL or a tracked file.
python3 - <<'PY' | local_pg_psql "$APPROVAL_TEST_DB" -v ON_ERROR_STOP=1 >/dev/null
import os,re
for key,credential in (('APPROVAL_WALLET_RUNTIME','APPROVAL_WALLET_DB_PASSWORD'),('APPROVAL_AUTH_RUNTIME','APPROVAL_AUTH_DB_PASSWORD')):
    role=os.environ[key];password=os.environ[credential]
    assert re.fullmatch(r'sw_test_[aw]r_[0-9a-f]{12}',role) and re.fullmatch(r'[0-9a-f]{64}',password)
    print(f'ALTER ROLE "{role}" PASSWORD \'{password}\';')
PY
export APPROVAL_DB_URL="$(local_pg_jdbc_url "$APPROVAL_TEST_DB")"
export APPROVAL_DB_USERNAME="$APPROVAL_AUTH_RUNTIME"
export APPROVAL_DB_PASSWORD="$APPROVAL_AUTH_DB_PASSWORD"
python3 "$APPROVAL_ROOT/wallet-approval/src/test/python/https_acceptance.py" "$APPROVAL_ROOT"
