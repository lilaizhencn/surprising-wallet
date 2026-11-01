#!/usr/bin/env bash
set -euo pipefail
umask 077

XMR_FLOW_ROOT=$(git rev-parse --show-toplevel)
source "$XMR_FLOW_ROOT/resources/scripts/regtest/local-postgres.sh"
: "${MPC_SIGNER_BIN:?set MPC_SIGNER_BIN to the built independent wallet-mpc-signer}"
: "${MPC_MONERO_BIN_DIR:?set MPC_MONERO_BIN_DIR to official Monero 0.18.5.4 binaries}"
for command in git mvn java python3; do
  command -v "$command" >/dev/null || { printf 'missing command: %s\n' "$command" >&2; exit 1; }
done
[[ -x "$MPC_SIGNER_BIN" && -x "$MPC_MONERO_BIN_DIR/monerod" && -x "$MPC_MONERO_BIN_DIR/monero-wallet-rpc" ]]
local_pg_require
XMR_FLOW_DB="surprising_wallet_test_xmr_native_$$"
XMR_FLOW_BUSINESS_DB="surprising_wallet_test_xmr_business_$$"
XMR_FLOW_RECOVERY_DB="surprising_wallet_test_xmr_recovery_$$"
local_pg_create "$XMR_FLOW_DB"
cleanup_xmr_flow() {
  local_pg_drop "$XMR_FLOW_DB" >/dev/null 2>&1 || true
  local_pg_drop "$XMR_FLOW_BUSINESS_DB" >/dev/null 2>&1 || true
  local_pg_drop "$XMR_FLOW_RECOVERY_DB" >/dev/null 2>&1 || true
}
trap cleanup_xmr_flow EXIT
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$XMR_FLOW_DB")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
cd "$XMR_FLOW_ROOT"

# A caller may supply the classpath from its just-completed Maven verification.
if [[ -z "${MPC_WALLET_TEST_CLASSPATH:-}" ]]; then
  mvn -pl wallet-api -am -Dtest=MoneroSubmissionIntegrationTest,MoneroDepositIntegrationTest,MoneroMpcResultVerifierTest,MpcSigningPersistenceIntegrationTest,ArchitectureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test
  MPC_WALLET_TEST_CLASSPATH=$(python3 - <<'PYCP'
import xml.etree.ElementTree as E
r=E.parse('wallet-api/target/surefire-reports/TEST-com.surprising.wallet.custody.MoneroDepositIntegrationTest.xml').getroot()
assert r.get('failures') == '0' and r.get('errors') == '0' and r.get('skipped') == '0'
print(next(p.get('value') for p in r.findall('./properties/property') if p.get('name') == 'java.class.path'))
PYCP
)
  export MPC_WALLET_TEST_CLASSPATH
fi
python3 resources/infra/mpc-signer/tests/monero_wallet_deposit_test.py "$MPC_SIGNER_BIN" "$MPC_MONERO_BIN_DIR"
local_pg_create "$XMR_FLOW_BUSINESS_DB"
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$XMR_FLOW_BUSINESS_DB")"
python3 resources/infra/mpc-signer/tests/monero_wallet_business_test.py "$MPC_SIGNER_BIN" "$MPC_MONERO_BIN_DIR"
local_pg_create "$XMR_FLOW_RECOVERY_DB"
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$XMR_FLOW_RECOVERY_DB")"
python3 resources/infra/mpc-signer/tests/monero_wallet_recovery_test.py "$MPC_SIGNER_BIN" "$MPC_MONERO_BIN_DIR"
printf 'XMR native deposit/reorg, formal outgoing/Gas and original SIGN material recovery passed; missing original signed material remains unknown.\n'
