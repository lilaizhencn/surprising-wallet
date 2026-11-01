#!/usr/bin/env bash
set -euo pipefail
umask 077
TRON_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the independent native signer}"
: "${MPC_TRON_TEST_HTTP_URL:?set MPC_TRON_TEST_HTTP_URL to the isolated TRE HTTP RPC}"
: "${MPC_TRON_TEST_FULL_NODE:?set MPC_TRON_TEST_FULL_NODE to its gRPC endpoint}"
: "${MPC_TRON_TEST_SOLIDITY_NODE:?set MPC_TRON_TEST_SOLIDITY_NODE to its solidity gRPC endpoint}"
: "${MPC_TRON_TEST_TOKEN_ARTIFACT:?set MPC_TRON_TEST_TOKEN_ARTIFACT to the existing isolated token fixture}"
command -v anvil >/dev/null || { printf 'Anvil is required by the native wallet probe\n' >&2; exit 1; }
source "$TRON_FLOW_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
TRON_FLOW_DB="surprising_wallet_test_tron_mpc_$$"
local_pg_create "$TRON_FLOW_DB"
trap 'local_pg_drop "$TRON_FLOW_DB" >/dev/null 2>&1 || true' EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$TRON_FLOW_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$TRON_FLOW_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=MpcTronTransactionCodecTest,TronExecutionReceiptTest,TronSubmissionIntegrationTest,TronAccountChainServiceTest,TronTransferRpcBoundaryTest,AccountChainWorkflowServiceTest,ArchitectureBoundaryTest,MpcPublicAddressCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 - "$TRON_FLOW_ROOT" "$MPC_SIGNER_BIN" <<'VERIFY'
import os,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
suite=ET.parse(root/'wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.TronSubmissionIntegrationTest.xml').getroot()
assert int(suite.get('tests','0'))>0 and all(int(suite.get(k,'0'))==0 for k in ('failures','errors','skipped'))
classpath=next(p.get('value') for p in suite.findall('./properties/property') if p.get('name')=='java.class.path')
env=os.environ.copy();env['MPC_WALLET_TEST_CLASSPATH']=classpath
raise SystemExit(subprocess.run(['python3',str(root/'resources/infra/mpc-signer/tests/node_test.py'),sys.argv[2]],cwd=root,env=env).returncode)
VERIFY
