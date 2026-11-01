#!/usr/bin/env bash
set -euo pipefail
umask 077

SOLANA_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
# Reuse a separately managed, isolated Agave test validator. Wallet tests create
# and remove only a temporary database on the installed local PostgreSQL 18.
export MPC_SOLANA_TEST_RPC_URL=${MPC_SOLANA_TEST_RPC_URL:-http://127.0.0.1:28899}
python3 - "$MPC_SOLANA_TEST_RPC_URL" <<'HEALTH'
import json
import sys
import urllib.parse
import urllib.request
endpoint = sys.argv[1]
parsed = urllib.parse.urlparse(endpoint)
if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
    raise SystemExit('Solana acceptance requires a separately managed loopback Agave test validator')
request = urllib.request.Request(endpoint, data=json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'getHealth'}).encode(), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=5) as response:
    if json.load(response).get('result') != 'ok':
        raise SystemExit('Agave test validator is not healthy')
HEALTH
exec bash "$SOLANA_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
