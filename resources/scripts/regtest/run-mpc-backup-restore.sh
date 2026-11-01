#!/usr/bin/env bash
set -euo pipefail
umask 077
RESTORE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_RELEASE_ARCHIVE:?set MPC_RELEASE_ARCHIVE to a fresh native package-release.sh archive}"
: "${MPC_MONERO_BIN_DIR:?set MPC_MONERO_BIN_DIR to official Monero0.18.5.4 binaries}"
source "$RESTORE_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
RESTORE_SOURCE_DB="surprising_wallet_test_mpc_restore_source_$$"
RESTORE_TARGET_DB="surprising_wallet_test_mpc_restore_target_$$"
trap 'local_pg_drop "$RESTORE_SOURCE_DB" >/dev/null 2>&1 || true; local_pg_drop "$RESTORE_TARGET_DB" >/dev/null 2>&1 || true' EXIT
local_pg_create "$RESTORE_SOURCE_DB"
local_pg_create "$RESTORE_TARGET_DB"
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$RESTORE_SOURCE_DB")"
export MPC_RESTORE_DB_URL="$(local_pg_jdbc_url "$RESTORE_TARGET_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$RESTORE_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=MpcSigningPersistenceIntegrationTest,ArchitectureBoundaryTest,MpcPublicAddressCodecTest,MoneroSigningPayloadTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 - "$RESTORE_ROOT" <<'VERIFY'
import os,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
report=root/'wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.MpcSigningPersistenceIntegrationTest.xml'
suite=ET.parse(report).getroot()
assert int(suite.get('tests','0'))==10 and all(int(suite.get(k,'0'))==0 for k in ['failures','errors','skipped'])
classpath=next(p.get('value') for p in suite.findall('./properties/property') if p.get('name')=='java.class.path')
env=os.environ.copy();env['MPC_WALLET_TEST_CLASSPATH']=classpath
raise SystemExit(subprocess.run(['python3',str(root/'resources/infra/mpc-signer/tests/backup_restore_test.py'),env['MPC_RELEASE_ARCHIVE'],env['MPC_MONERO_BIN_DIR']],cwd=root,env=env).returncode)
VERIFY
