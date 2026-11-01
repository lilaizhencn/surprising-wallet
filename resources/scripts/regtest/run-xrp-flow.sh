#!/usr/bin/env bash
set -euo pipefail
umask 077
XRP_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the independent native signer}"
: "${MPC_XRP_TEST_RPC_URL:?set MPC_XRP_TEST_RPC_URL to the isolated xrpld standalone RPC}"
command -v anvil >/dev/null || { printf 'Anvil is required by the native wallet probe\n' >&2; exit 1; }
source "$XRP_FLOW_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
XRP_FLOW_DB="surprising_wallet_test_xrp_mpc_$$"
local_pg_create "$XRP_FLOW_DB"
trap 'local_pg_drop "$XRP_FLOW_DB" >/dev/null 2>&1 || true' EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$XRP_FLOW_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$XRP_FLOW_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=XrpAddressGenerationTest,XrpIssuedCurrencyTest,MpcXrpTransactionCodecTest,XrpExecutionReceiptTest,XrpSubmissionIntegrationTest,ArchitectureBoundaryTest,MpcPublicAddressCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 - "$XRP_FLOW_ROOT" "$MPC_SIGNER_BIN" <<'VERIFY'
import os,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
suite=ET.parse(root/'wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.XrpSubmissionIntegrationTest.xml').getroot()
assert int(suite.get('tests','0'))>0 and all(int(suite.get(k,'0'))==0 for k in ('failures','errors','skipped'))
classpath=next(p.get('value') for p in suite.findall('./properties/property') if p.get('name')=='java.class.path')
env=os.environ.copy();env['MPC_WALLET_TEST_CLASSPATH']=classpath
raise SystemExit(subprocess.run(['python3',str(root/'resources/infra/mpc-signer/tests/node_test.py'),sys.argv[2]],cwd=root,env=env).returncode)
VERIFY
