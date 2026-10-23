#!/usr/bin/env bash
set -euo pipefail
WALLET_7702_ROOT=$(git rev-parse --show-toplevel)
source "$WALLET_7702_ROOT/resources/scripts/regtest/local-postgres.sh"
WALLET_7702_BUILD=$(mktemp -d /tmp/surprising-wallet-7702-flow.XXXXXX)
WALLET_7702_DB="surprising_wallet_test_7702_flow_$$"
WALLET_7702_NODE_PID=""
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$WALLET_7702_NODE_PID" ]]; then
    kill "$WALLET_7702_NODE_PID" 2>/dev/null || true
    wait "$WALLET_7702_NODE_PID" 2>/dev/null || true
  fi
  local_pg_drop "$WALLET_7702_DB" >/dev/null 2>&1 || true
  if [[ "$status" -ne 0 ]] && [[ -f "$WALLET_7702_BUILD/deploy.log" ]]; then
    tail -40 "$WALLET_7702_BUILD/deploy.log" >&2
  fi
  if [[ "$WALLET_7702_BUILD" == /tmp/surprising-wallet-7702-flow.* ]] \
      && [[ -d "$WALLET_7702_BUILD" ]]; then
    trash "$WALLET_7702_BUILD"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM
for command in curl git jq mvn node npm rsync trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require
if curl -fsS -m 1 -H 'content-type: application/json' --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' http://127.0.0.1:8545 >/dev/null 2>&1; then
  printf 'port 8545 already serves a node\n' >&2
  exit 1
fi
if [[ ! -d "$WALLET_7702_ROOT/resources/infra/evm-fork/node_modules" ]]; then
  npm --prefix "$WALLET_7702_ROOT/resources/infra/evm-fork" ci
fi
rsync -a --exclude=.git --exclude=.codegraph --exclude=target --exclude=node_modules --exclude=artifacts --exclude=logs --exclude=keypairs "$WALLET_7702_ROOT/" "$WALLET_7702_BUILD/"
ln -s "$WALLET_7702_ROOT/resources/infra/evm-fork/node_modules" "$WALLET_7702_BUILD/resources/infra/evm-fork/node_modules"
local_pg_create "$WALLET_7702_DB"
(
  cd "$WALLET_7702_BUILD/resources/infra/evm-fork"
  exec env HARDHAT_CHAIN_ID=31337 HARDHAT_DISABLE_TELEMETRY_PROMPT=true ./node_modules/.bin/hardhat node --hostname 127.0.0.1 --port 8545
) > "$WALLET_7702_BUILD/node.log" 2>&1 &
WALLET_7702_NODE_PID=$!
for attempt in $(seq 1 60); do
  response=$(curl -fsS -m 1 -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    http://127.0.0.1:8545 2>/dev/null || true)
  if [[ -n "$response" ]] && [[ $(jq -r .result <<<"$response") == 0x7a69 ]]; then break; fi
  kill -0 "$WALLET_7702_NODE_PID"
  if [[ "$attempt" == 60 ]]; then
    printf 'local Hardhat Prague node did not become ready\n' >&2
    exit 1
  fi
  sleep 0.5
done
EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266 \
EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266 \
EIP7702_DEPLOYMENT_FILE="$WALLET_7702_BUILD/deployment.json" \
HARDHAT_CHAIN_ID=31337 HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
npm --prefix "$WALLET_7702_BUILD/resources/infra/evm-fork" run deploy:7702 > "$WALLET_7702_BUILD/deploy.log" 2>&1
WALLET_7702_COLLECTOR=$(jq -r .collectorAddress "$WALLET_7702_BUILD/deployment.json")
WALLET_7702_DELEGATE=$(jq -r .delegateAddress "$WALLET_7702_BUILD/deployment.json")
SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$WALLET_7702_DB")" \
SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER" SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
mvn -B -ntp -f "$WALLET_7702_BUILD/pom.xml" -pl wallet-api -am test \
  -Dtest=Evm7702ProductionFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.production.enabled=true -Devm.7702.test.chain=ETH -Devm.7702.test.chain-id=31337 \
  -Devm.7702.collector="$WALLET_7702_COLLECTOR" -Devm.7702.delegate="$WALLET_7702_DELEGATE"

printf 'EIP-7702 local production workflow passed; temporary PostgreSQL 18 database will be removed\n'
