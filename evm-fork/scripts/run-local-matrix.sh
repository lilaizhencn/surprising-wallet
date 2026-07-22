#!/usr/bin/env bash
set -euo pipefail

EVM_MATRIX_ROOT=$(git rev-parse --show-toplevel)
source "$EVM_MATRIX_ROOT/scripts/regtest/local-postgres.sh"
EVM_MATRIX_TMP=$(mktemp -d -t surprising-evm-matrix.XXXXXX)
EVM_MATRIX_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-evm-matrix-build.XXXXXX)
EVM_MATRIX_DB="surprising_wallet_test_evm_matrix_$$"
EVM_MATRIX_NODE_PID=""

EVM_CHAINS=(
  "ARBITRUM|ETH_ARB|421614|USDC,USDT"
  "AVAX_C|AVAX_C|43113|USDC,USDT"
  "BASE|ETH_BASE|84532|USDC,USDT"
  "BNB|BNB|97|USDC,USDT"
  "ETH|ETH|11155111|USDC,USDT"
  "HYPEREVM|HYPE|998|USDC"
  "LINEA|ETH_LINEA|59141|USDC"
  "MANTLE|MNT|5003|"
  "OPTIMISM|ETH_OP|11155420|USDC,USDT"
  "POLYGON|POL|80002|USDC,USDT"
  "SCROLL|ETH_SCROLL|534351|USDC"
  "UNICHAIN|ETH_UNICHAIN|1301|USDC"
)

stop_node() {
  if [[ -n "$EVM_MATRIX_NODE_PID" ]] && kill -0 "$EVM_MATRIX_NODE_PID" 2>/dev/null; then
    kill "$EVM_MATRIX_NODE_PID" 2>/dev/null || true
    wait "$EVM_MATRIX_NODE_PID" 2>/dev/null || true
  fi
  EVM_MATRIX_NODE_PID=""
  for attempt in $(seq 1 20); do
    if ! curl -fsS -m 1 \
        -H 'content-type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
        http://127.0.0.1:8545 >/dev/null 2>&1; then
      return
    fi
    sleep 0.25
  done
  printf 'Hardhat RPC did not stop cleanly on port 8545\n' >&2
  return 1
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  stop_node
  local_pg_drop "$EVM_MATRIX_DB" >/dev/null 2>&1 || true
  if [[ "$EVM_MATRIX_TMP" == *"/surprising-evm-matrix."* ]] && [[ -d "$EVM_MATRIX_TMP" ]]; then
    trash "$EVM_MATRIX_TMP"
  fi
  if [[ "$EVM_MATRIX_BUILD_ROOT" == /tmp/surprising-wallet-evm-matrix-build.* ]] \
      && [[ -d "$EVM_MATRIX_BUILD_ROOT" ]]; then
    trash "$EVM_MATRIX_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl git jq ln mvn node npm psql rsync trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require

if curl -fsS -m 1 \
    -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    http://127.0.0.1:8545 >/dev/null 2>&1; then
  printf 'port 8545 is already serving an EVM node; stop it before running this isolated test\n' >&2
  exit 1
fi

if [[ ! -d "$EVM_MATRIX_ROOT/evm-fork/node_modules" ]]; then
  npm --prefix "$EVM_MATRIX_ROOT/evm-fork" ci
fi

rsync -a \
  --exclude .git \
  --exclude .codegraph \
  --exclude target \
  --exclude node_modules \
  --exclude artifacts \
  --exclude logs \
  "$EVM_MATRIX_ROOT/" "$EVM_MATRIX_BUILD_ROOT/"
ln -s "$EVM_MATRIX_ROOT/evm-fork/node_modules" "$EVM_MATRIX_BUILD_ROOT/evm-fork/node_modules"

local_pg_create "$EVM_MATRIX_DB"

wait_for_rpc() {
  local expected_chain_id=$1
  local log_file=$2
  for attempt in $(seq 1 60); do
    local response
    response=$(curl -fsS -m 1 \
      -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
      http://127.0.0.1:8545 2>/dev/null || true)
    if [[ -n "$response" ]] && [[ $(jq -r '.result' <<<"$response") == $(printf '0x%x' "$expected_chain_id") ]]; then
      return 0
    fi
    if ! kill -0 "$EVM_MATRIX_NODE_PID" 2>/dev/null; then
      printf 'Hardhat stopped before becoming ready\n' >&2
      tail -80 "$log_file" >&2 || true
      return 1
    fi
    if [[ "$attempt" == 60 ]]; then
      printf 'Hardhat did not become ready for chain id %s\n' "$expected_chain_id" >&2
      return 1
    fi
    sleep 1
  done
}

for definition in "${EVM_CHAINS[@]}"; do
  IFS='|' read -r chain native_symbol chain_id token_symbols <<<"$definition"

  local_pg_psql "$EVM_MATRIX_DB" -q -v ON_ERROR_STOP=1 \
    -f "$EVM_MATRIX_BUILD_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
    >"$EVM_MATRIX_TMP/$chain.schema.log"

  configured_tokens=$(local_pg_psql "$EVM_MATRIX_DB" -Atqc \
    "select coalesce(string_agg(symbol, ',' order by symbol), '') from token_config where chain='$chain' and enabled=true")
  expected_tokens=$(tr ',' '\n' <<<"$token_symbols" | sed '/^$/d' | sort | paste -sd, -)
  if [[ "$configured_tokens" != "$expected_tokens" ]]; then
    printf '%s token matrix mismatch: database=%s expected=%s\n' \
      "$chain" "$configured_tokens" "$expected_tokens" >&2
    exit 1
  fi

  hardhat_log="$EVM_MATRIX_TMP/$chain.hardhat.log"
  (
    cd "$EVM_MATRIX_BUILD_ROOT/evm-fork"
    exec env HARDHAT_CHAIN_ID="$chain_id" HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
      ./node_modules/.bin/hardhat node --hostname 127.0.0.1 --port 8545
  ) >"$hardhat_log" 2>&1 &
  EVM_MATRIX_NODE_PID=$!
  wait_for_rpc "$chain_id" "$hardhat_log"

  EVM_CHAIN="$chain" \
  TOKEN_SYMBOLS="$token_symbols" \
  PG_URL="$(local_pg_uri "$EVM_MATRIX_DB")" \
  DEPLOYMENT_OUT_DIR="$EVM_MATRIX_TMP/deployments" \
    npm --prefix "$EVM_MATRIX_BUILD_ROOT/evm-fork" run deploy:mock >/dev/null

  mvn -q -f "$EVM_MATRIX_BUILD_ROOT/pom.xml" \
    -pl backendservices/wallet-parent/wallet-service -am \
    -Dtest=EvmForkFullChainIntegrationTest,EvmForkMultiUserBusinessFlowIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Devm.fork.enabled=true \
    -Devm.multiuser.enabled=true \
    -Devm.fork.chain="$chain" \
    -Devm.native.symbol="$native_symbol" \
    -Devm.expected.chainId="$chain_id" \
    -Devm.confirmations=1 \
    -Devm.db.url="$(local_pg_jdbc_url "$EVM_MATRIX_DB")" \
    -Devm.db.user="$REGTEST_PG_USER" \
    -Devm.db.password="$REGTEST_PG_PASSWORD" \
    test

  negative_balances=$(local_pg_psql "$EVM_MATRIX_DB" -Atqc \
    "select count(*) from ledger_balance where chain='$chain' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)")
  nonterminal_orders=$(local_pg_psql "$EVM_MATRIX_DB" -Atqc \
    "select count(*) from withdrawal_order where chain='$chain' and status not in ('CONFIRMED','FAILED','CANCELLED')")
  if [[ "$negative_balances" != 0 || "$nonterminal_orders" != 0 ]]; then
    printf '%s post-test audit failed: negative_balances=%s nonterminal_orders=%s\n' \
      "$chain" "$negative_balances" "$nonterminal_orders" >&2
    exit 1
  fi

  printf '%s PASS native=%s tokens=%s\n' "$chain" "$native_symbol" "${token_symbols:-none}"
  stop_node
done

printf 'EVM local matrix passed for %s chains\n' "${#EVM_CHAINS[@]}"
