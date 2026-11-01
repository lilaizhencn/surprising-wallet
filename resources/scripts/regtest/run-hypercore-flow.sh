#!/usr/bin/env bash
set -euo pipefail
umask 077
HYPERCORE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the independent native signer}"
: "${MPC_HYPERCORE_ACCEPTANCE_ROOT:?set MPC_HYPERCORE_ACCEPTANCE_ROOT to existing funded testnet materials}"
: "${MPC_HYPERCORE_RECIPIENT:?set MPC_HYPERCORE_RECIPIENT to an activated testnet recipient}"
: "${MPC_HYPERCORE_FUNDED_RUN_ID:?set a stable run ID; preserve it when retrying}"
: "${MPC_HYPERCORE_FUNDED_PAYER_ROLE:?select original source or collector}"
: "${MPC_HYPERCORE_FUNDED_PHASE:?select the funded acceptance phase}"
source "$HYPERCORE_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
HYPERCORE_BUILD_DB="surprising_wallet_test_hypercore_build_$$"
HYPERCORE_LEDGER_DB=${MPC_HYPERCORE_ACCEPTANCE_DB:-surprising_wallet_test_hypercore_mpc_acceptance}
local_pg_validate_test_database "$HYPERCORE_LEDGER_DB"
[[ "$HYPERCORE_BUILD_DB" != "$HYPERCORE_LEDGER_DB" ]] || exit 1
[[ "$MPC_HYPERCORE_ACCEPTANCE_ROOT" = /* && -f "$MPC_HYPERCORE_ACCEPTANCE_ROOT/acceptance-public.json" ]] || {
  printf 'existing absolute funded-material directory required\n' >&2; exit 1;
}
HYPERCORE_FUNDED_STARTED=false
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$HYPERCORE_FUNDED_STARTED" == true ]]; then
    local backup
    backup=$(mktemp "$MPC_HYPERCORE_ACCEPTANCE_ROOT/funded-ledger.XXXXXX")
    if PGPASSWORD="$REGTEST_PG_PASSWORD" pg_dump -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" \
        -U "$REGTEST_PG_USER" --no-owner --no-privileges -d "$HYPERCORE_LEDGER_DB" -f "$backup"; then
      mv "$backup" "$MPC_HYPERCORE_ACCEPTANCE_ROOT/funded-ledger-latest.pgsql"
    else
      printf 'funded ledger backup failed; preserve the existing database for recovery\n' >&2
      status=1
    fi
  fi
  local_pg_drop "$HYPERCORE_BUILD_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM
local_pg_create "$HYPERCORE_BUILD_DB"
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$HYPERCORE_BUILD_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$HYPERCORE_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=MpcHyperCoreActionCodecTest,HyperCoreSubmissionIntegrationTest,MpcSigningPersistenceIntegrationTest,ArchitectureBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
HYPERCORE_EXISTS=$(local_pg_psql "$REGTEST_PG_ADMIN_DB" -Atqc "select count(*) from pg_database where datname='$HYPERCORE_LEDGER_DB'")
if [[ "$HYPERCORE_EXISTS" == 0 ]]; then
  [[ -f "$MPC_HYPERCORE_ACCEPTANCE_ROOT/funded-ledger-latest.pgsql" ]] || {
    printf 'funded ledger snapshot required; this command does not reinitialize funded identities\n' >&2; exit 1;
  }
  PGPASSWORD="$REGTEST_PG_PASSWORD" createdb -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" -U "$REGTEST_PG_USER" "$HYPERCORE_LEDGER_DB"
  if ! local_pg_psql "$HYPERCORE_LEDGER_DB" -X -v ON_ERROR_STOP=1 \
      -f "$MPC_HYPERCORE_ACCEPTANCE_ROOT/funded-ledger-latest.pgsql" \
      >"$MPC_HYPERCORE_ACCEPTANCE_ROOT/funded-ledger-restore-private.log" 2>&1; then
    printf 'funded snapshot restore failed; inspect protected fixture diagnostics\n' >&2; exit 1
  fi
fi
[[ $(local_pg_psql "$HYPERCORE_LEDGER_DB" -Atqc "select count(*) from information_schema.columns where table_schema='public' and table_name='wallet_mpc_key_epoch' and column_name in ('refresh_actor','refresh_reason')") == 2 ]] || {
  printf 'funded fixture must match the current target MPC schema before execution\n' >&2; exit 1;
}
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$HYPERCORE_LEDGER_DB")"
HYPERCORE_FUNDED_STARTED=true
python3 - "$HYPERCORE_ROOT" <<'VERIFY'
import os,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
suite=ET.parse(root/'wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.MpcSigningPersistenceIntegrationTest.xml').getroot()
assert int(suite.get('tests','0'))==10 and all(int(suite.get(k,'0'))==0 for k in ('failures','errors','skipped'))
classpath=next(p.get('value') for p in suite.findall('./properties/property') if p.get('name')=='java.class.path')
env=os.environ.copy();env['MPC_WALLET_TEST_CLASSPATH']=classpath
raise SystemExit(subprocess.run([env.get('MPC_HYPERCORE_PYTHON','python3'),str(root/'resources/infra/mpc-signer/tests/hypercore_funded_test.py'),env['MPC_SIGNER_BIN'],env['MPC_HYPERCORE_ACCEPTANCE_ROOT'],env['MPC_HYPERCORE_RECIPIENT']],cwd=root,env=env).returncode)
VERIFY
