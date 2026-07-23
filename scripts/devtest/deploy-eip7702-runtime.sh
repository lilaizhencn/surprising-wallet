#!/usr/bin/env bash
set -euo pipefail

DEVTEST_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEVTEST_RPC_URL=${EVM_RPC_URL:-http://127.0.0.1:8545}
DEVTEST_EXPECTED_CHAIN_ID=${EVM_EXPECTED_CHAIN_ID_HEX:-0x7a69}
DEVTEST_DEPLOYMENT_DIR=${EIP7702_DEPLOYMENT_DIR:-/var/lib/surprising-wallet/evm-devtest}
DEVTEST_DEPLOYMENT_FILE="$DEVTEST_DEPLOYMENT_DIR/ETH-EIP7702-devtest.json"

case "$DEVTEST_RPC_URL" in
  http://127.0.0.1:*|http://localhost:*|http://\[::1\]:*) ;;
  *)
    printf 'devtest EIP-7702 deployment requires a loopback RPC URL\n' >&2
    exit 1
    ;;
esac
if [[ -z ${PG_URL:-} ]]; then
  printf 'PG_URL is required for devtest EIP-7702 deployment\n' >&2
  exit 1
fi

chain_id=$(curl -fsS --max-time 2 \
  -H 'content-type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
  "$DEVTEST_RPC_URL" | jq -r '.result // empty')
if [[ $chain_id != "$DEVTEST_EXPECTED_CHAIN_ID" ]]; then
  printf 'unexpected devtest chain id: %s\n' "$chain_id" >&2
  exit 1
fi

accounts=$(curl -fsS --max-time 2 \
  -H 'content-type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"eth_accounts","params":[]}' \
  "$DEVTEST_RPC_URL")
admin_address=$(jq -r '.result[0] // empty' <<<"$accounts")
if [[ ! $admin_address =~ ^0x[0-9a-fA-F]{40}$ ]]; then
  printf 'Hardhat devtest admin account is unavailable\n' >&2
  exit 1
fi

relayer_address=$(psql "$PG_URL" -Atqc \
  "select address from chain_address where tenant_id is null and chain = 'ETH' and wallet_role = 'EIP7702_RELAYER' and enabled = true")
if [[ ! $relayer_address =~ ^0x[0-9a-fA-F]{40}$ ]]; then
  printf 'unique platform EIP-7702 relayer is unavailable\n' >&2
  exit 1
fi

install -d -m 0750 "$DEVTEST_DEPLOYMENT_DIR"
EIP7702_ADMIN_ADDRESS=$admin_address \
EIP7702_RELAYER_ADDRESS=$relayer_address \
EIP7702_DEPLOYMENT_FILE=$DEVTEST_DEPLOYMENT_FILE \
EIP7702_ALLOW_OVERWRITE=true \
  npm --prefix "$DEVTEST_ROOT/evm-fork" run deploy:7702

EIP7702_DEPLOYMENT_FILE=$DEVTEST_DEPLOYMENT_FILE \
EVM_VERIFY_RPC_URL=$DEVTEST_RPC_URL \
  npm --prefix "$DEVTEST_ROOT/evm-fork" run verify:7702

curl -fsS --max-time 2 \
  -H 'content-type: application/json' \
  --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hardhat_setBalance\",\"params\":[\"$relayer_address\",\"0x56bc75e2d63100000\"]}" \
  "$DEVTEST_RPC_URL" | jq -e '.result == true' >/dev/null

EIP7702_DEPLOYMENT_FILE=$DEVTEST_DEPLOYMENT_FILE \
EVM_CHAIN=ETH \
EVM_NETWORK=devtest \
  node "$DEVTEST_ROOT/evm-fork/scripts/configure-eip7702-devtest.js"
