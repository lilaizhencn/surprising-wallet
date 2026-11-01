#!/usr/bin/env bash
set -euo pipefail
umask 077
TON_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
# Reuse an isolated MyLocalTon genesis and TON HTTP API. No auxiliary faucet/indexer database.
export MPC_TON_TEST_RPC_URL=${MPC_TON_TEST_RPC_URL:-http://127.0.0.1:28882}
: "${MPC_TON_TEST_FUNDER_KEY_FILE:?Set an absolute protected key-file path for this isolated genesis funding fixture}"
export MPC_TON_TEST_FUNDER_KEY_FILE
python3 - "$MPC_TON_TEST_RPC_URL" "$MPC_TON_TEST_FUNDER_KEY_FILE" <<'HEALTH'
import json, pathlib, stat, sys, urllib.parse, urllib.request
endpoint=sys.argv[1]; uri=urllib.parse.urlparse(endpoint); key=pathlib.Path(sys.argv[2])
if uri.scheme!='http' or uri.hostname!='127.0.0.1':
    raise SystemExit('TON acceptance requires a separately managed isolated loopback TON HTTP API')
if not key.is_absolute() or not key.is_file() or key.stat().st_size!=32 or stat.S_IMODE(key.stat().st_mode)&0o077:
    raise SystemExit('The local genesis fixture key must be an absolute 32-byte file accessible only by its owner')
with urllib.request.urlopen(endpoint.rstrip('/')+'/getMasterchainInfo',timeout=10) as response:
    data=json.load(response)
    if not data.get('ok') or data.get('result',{}).get('last',{}).get('seqno',0)<=0:
        raise SystemExit('The isolated TON node has no confirmed blocks')
HEALTH
exec bash "$TON_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
