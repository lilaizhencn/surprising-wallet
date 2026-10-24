#!/usr/bin/env bash
set -euo pipefail

CUSTODY_DB_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$CUSTODY_DB_ROOT/resources/scripts/regtest/local-postgres.sh"

CUSTODY_DB_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-custody-db-tests.XXXXXX)
CUSTODY_DB_NAME="surprising_wallet_test_custody_$$"
CUSTODY_DB_TESTS=${CUSTODY_DB_TESTS:-WalletTokenValidationIntegrationTest,CustodyWithdrawalCreationIntegrationTest,AptosDatabaseFlowIntegrationTest,SuiDatabaseFlowIntegrationTest,CustodyAddressRuntimeIntegrationTest,ChainProfileRepositoryIntegrationTest,RuntimeConfigurationIntegrationTest,TokenConfigurationIntegrationTest,CustodyDepositProjectionIntegrationTest,CustodyWebhookLifecycleIntegrationTest,CustodySecurityIntegrationTest,CustodyAuthenticationIntegrationTest,CustodyOperationsIntegrationTest,CustodyOperationalQueriesIntegrationTest,CustodyControlPlaneQueriesIntegrationTest,CustodyAddressAllocationIntegrationTest,CustodyTenantIsolationIntegrationTest,CustodyDepositFinalityIntegrationTest,CustodyAssetRecoveryIntegrationTest,Evm7702ProductionFlowIntegrationTest,CustodyEventPublicationIntegrationTest,CustodyWithdrawalProjectionIntegrationTest,CustodyGasAccountingIntegrationTest,WithdrawalSettlementIntegrationTest,EvmNonceReservationIntegrationTest,UtxoSigningSubmissionIntegrationTest,BtcCollectionRbfConcurrencyIntegrationTest,UtxoSigningPipelineIntegrationTest,UtxoDepositWorkflowIntegrationTest,EvmBatchOwnershipIntegrationTest,AccountSequenceReservationIntegrationTest,EvmUnknownAttemptRecoveryIntegrationTest,EvmBatchRowsIntegrationTest}

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

for command in git mvn rsync trash python3; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require
local_pg_create "$CUSTODY_DB_NAME"
local_pg_psql "$CUSTODY_DB_NAME" -q -v ON_ERROR_STOP=1 \
  -f "$CUSTODY_DB_ROOT/resources/docs/db/surprising-wallet-init-pgsql.sql" \
  >"$CUSTODY_DB_BUILD_ROOT/schema-init.log"

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
  -pl wallet-api -am \
  -Dtest="$CUSTODY_DB_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Daptos.db.enabled=true \
  -Dsui.db.enabled=true \
  "$@" \
  test

python3 - "$CUSTODY_DB_BUILD_ROOT/wallet-api/target/surefire-reports" "$CUSTODY_DB_TESTS" <<'REPORTS'
import fnmatch
import pathlib
import sys
import xml.etree.ElementTree as ET

reports = [ET.parse(path).getroot() for path in pathlib.Path(sys.argv[1]).glob('TEST-*.xml')]
for selector in sys.argv[2].split(','):
    selector = selector.strip()
    if not selector:
        continue
    if selector.startswith('!'):
        continue
    class_pattern, _, methods = selector.partition('#')
    matching = [suite for suite in reports if fnmatch.fnmatch(suite.get('name', ''), class_pattern)
                or fnmatch.fnmatch(suite.get('name', '').rsplit('.', 1)[-1], class_pattern)]
    if not matching:
        raise SystemExit('requested test has no report: ' + selector)
    if methods:
        names = [case.get('name', '') for suite in matching for case in suite.findall('testcase')]
        for method in methods.split('+'):
            if not any(fnmatch.fnmatch(name, method)
                       or fnmatch.fnmatch(name.split('(')[0].split('[')[0].strip(), method) for name in names):
                raise SystemExit('requested method has no report: ' + selector)
executed = sum(int(suite.get('tests', 0)) - int(suite.get('skipped', 0)) for suite in reports)
skipped = sum(int(suite.get('skipped', 0)) for suite in reports)
if any(int(suite.get('failures', 0)) or int(suite.get('errors', 0)) for suite in reports):
    raise SystemExit('selected tests contain failures or errors')
if executed <= 0:
    raise SystemExit('no selected test executed')
print(f'Custody DB integration tests passed on local PostgreSQL 18: {executed} executed, {skipped} skipped')
REPORTS
