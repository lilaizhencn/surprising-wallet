#!/usr/bin/env bash
set -euo pipefail
umask 077

APTOS_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
# The managed localnet must omit --with-indexer-api. This runner never starts a
# database. The wallet runner uses the installed PostgreSQL 18 and a temporary DB.
export MPC_APTOS_TEST_RPC_URL=${MPC_APTOS_TEST_RPC_URL:-http://127.0.0.1:8080/v1}
export MPC_APTOS_TEST_FAUCET_URL=${MPC_APTOS_TEST_FAUCET_URL:-http://127.0.0.1:18081}
: "${MPC_APTOS_TEST_FA_FIXTURE:?Set the path to an isolated localnet public FA fixture JSON; see resources/infra/aptos/fa-test/README.md}"
command -v aptos >/dev/null
python3 - "$MPC_APTOS_TEST_RPC_URL" "$MPC_APTOS_TEST_FAUCET_URL" "$MPC_APTOS_TEST_FA_FIXTURE" <<'HEALTH'
import json
import pathlib
import sys
import urllib.parse
import urllib.request
for value in sys.argv[1:3]:
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
        raise SystemExit('Aptos acceptance requires a separately managed loopback localnet and faucet')
fixture = json.loads(pathlib.Path(sys.argv[3]).read_text())
if fixture.get('endpoint') != sys.argv[1] or not all(fixture.get(key) for key in ('publisher', 'adminDirectory', 'USDC', 'USDT')):
    raise SystemExit('Aptos FA public fixture does not identify this localnet and isolated mint profile')
with urllib.request.urlopen(sys.argv[1], timeout=5) as response:
    if json.load(response).get('chain_id') != 4:
        raise SystemExit('Aptos acceptance requires local chain ID 4')
HEALTH
exec bash "$APTOS_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
