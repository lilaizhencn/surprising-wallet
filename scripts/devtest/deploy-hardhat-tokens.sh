#!/usr/bin/env bash
set -euo pipefail

DEVTEST_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEVTEST_RPC_URL=${EVM_RPC_URL:-http://127.0.0.1:8545}
DEVTEST_EXPECTED_CHAIN_ID=${EVM_EXPECTED_CHAIN_ID_HEX:-0x7a69}

case "$DEVTEST_RPC_URL" in
  http://127.0.0.1:*|http://localhost:*|http://\[::1\]:*) ;;
  *)
    printf 'devtest token deployment requires a loopback RPC URL\n' >&2
    exit 1
    ;;
esac

if [[ -z "${PG_URL:-}" ]]; then
  printf 'PG_URL is required for devtest token deployment\n' >&2
  exit 1
fi

for attempt in $(seq 1 60); do
  chain_id=$(curl -fsS --max-time 1 \
    -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    "$DEVTEST_RPC_URL" 2>/dev/null | jq -r '.result // empty' || true)
  if [[ "$chain_id" == "$DEVTEST_EXPECTED_CHAIN_ID" ]]; then
    exec npm --prefix "$DEVTEST_ROOT/evm-fork" run deploy:mock
  fi
  sleep 1
done

printf 'Hardhat devtest RPC did not become ready with chain id %s\n' \
  "$DEVTEST_EXPECTED_CHAIN_ID" >&2
exit 1
