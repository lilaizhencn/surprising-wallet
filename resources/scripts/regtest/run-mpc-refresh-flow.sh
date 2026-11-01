#!/usr/bin/env bash
set -euo pipefail
umask 077
REFRESH_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the built independent native signer}"
source "$REFRESH_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
REFRESH_DATABASE="surprising_wallet_test_mpc_refresh_flow_$$"
local_pg_create "$REFRESH_DATABASE"
trap 'local_pg_drop "$REFRESH_DATABASE" >/dev/null 2>&1 || true' EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$REFRESH_DATABASE")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$REFRESH_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=MpcSigningPersistenceIntegrationTest,ArchitectureBoundaryTest,MpcPublicAddressCodecTest,NearStorageReceiptTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 - "$REFRESH_ROOT" "$MPC_SIGNER_BIN" <<'VERIFY'
import os,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
report=root/'wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.MpcSigningPersistenceIntegrationTest.xml'
suite=ET.parse(report).getroot()
assert int(suite.get('tests','0'))==10 and all(int(suite.get(k,'0'))==0 for k in ['failures','errors','skipped'])
classpath=next(p.get('value') for p in suite.findall('./properties/property') if p.get('name')=='java.class.path')
env=os.environ.copy();env['MPC_WALLET_TEST_CLASSPATH']=classpath
raise SystemExit(subprocess.run(['python3',str(root/'resources/infra/mpc-signer/tests/epoch_refresh_java_test.py'),sys.argv[2]],cwd=root,env=env).returncode)
VERIFY
