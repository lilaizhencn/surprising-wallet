#!/usr/bin/env bash
set -euo pipefail
umask 077
NEAR_STORAGE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the built native wallet-mpc-signer}"
: "${MPC_NEAR_TEST_FIXTURE:?set MPC_NEAR_TEST_FIXTURE to the protected isolated NEAR fixture}"
source "$NEAR_STORAGE_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
NEAR_STORAGE_DATABASE="surprising_wallet_test_near_storage_$$"
local_pg_create "$NEAR_STORAGE_DATABASE"
cleanup_near_storage() { local_pg_drop "$NEAR_STORAGE_DATABASE" >/dev/null 2>&1 || true; }
trap cleanup_near_storage EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$NEAR_STORAGE_DATABASE")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -B -ntp -f "$NEAR_STORAGE_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=NearSubmissionIntegrationTest,NearStorageReceiptTest,NearRpcClientTest,ArchitectureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 - "$NEAR_STORAGE_ROOT" "$MPC_SIGNER_BIN" <<'VERIFY'
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
report = root / "wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.NearSubmissionIntegrationTest.xml"
suite = ET.parse(report).getroot()
assert int(suite.get("tests", "0")) == 24
assert all(int(suite.get(name, "0")) == 0 for name in ("failures", "errors", "skipped"))
classpath = next(prop.get("value") for prop in suite.findall("./properties/property") if prop.get("name") == "java.class.path")
assert (root / "wallet-api/target/test-classes/com/surprising/wallet/custody/MpcNearRegistrationNativeProbe.class").is_file()
env = os.environ.copy()
env["MPC_WALLET_TEST_CLASSPATH"] = classpath
result = subprocess.run(["python3", str(root / "resources/infra/mpc-signer/tests/near_registration_test.py"), sys.argv[2]], cwd=root, env=env)
raise SystemExit(result.returncode)
VERIFY
