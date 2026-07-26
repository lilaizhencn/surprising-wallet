#!/usr/bin/env bash
set -euo pipefail

EVM_MATRIX_ROOT=$(git rev-parse --show-toplevel)
EVM_MATRIX_HARDHAT_ROOT="$EVM_MATRIX_ROOT/resources/infra/evm-fork"
source "$EVM_MATRIX_ROOT/resources/scripts/regtest/local-postgres.sh"
EVM_MATRIX_TMP=$(mktemp -d -t surprising-evm-matrix.XXXXXX)
EVM_MATRIX_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-evm-matrix-build.XXXXXX)
EVM_MATRIX_DB="surprising_wallet_test_evm_matrix_$$"
EVM_MATRIX_NODE_PID=""

EVM_CHAINS=(
  "ARBITRUM|ETH_ARB|421614|USDC,USDT|sepolia|false"
  "AVAX_C|AVAX_C|43113|USDC,USDT|fuji|false"
  "BASE|ETH_BASE|84532|USDC,USDT|sepolia|false"
  "BERACHAIN|BERA|80069|USDC,USDT0|bepolia|true"
  "BNB|BNB|97|USDC,USDT|testnet|false"
  "CELO|CELO|11142220|USDC,USDT|celo-sepolia|true"
  "ETH|ETH|11155111|USDC,USDT|sepolia|false"
  "GNOSIS|XDAI|10200|USDC,USDT|chiado|true"
  "HYPEREVM|HYPE|998|USDC|testnet|false"
  "INK|ETH_INK|763373|USDC_E,USDT0|sepolia|true"
  "KATANA|ETH_KATANA|737373|USDC,USDT|bokuto|true"
  "LINEA|ETH_LINEA|59141|USDC|sepolia|false"
  "LISK|ETH_LISK|4202|USDC_E|sepolia|true"
  "MANTLE|MNT|5003||sepolia|false"
  "MODE|ETH_MODE|919|USDC,USDT|sepolia|true"
  "MONAD|MON|10143|USDC,USDT0|testnet|true"
  "TAIKO|ETH_TAIKO|167013||hoodi|true"
  "OPTIMISM|ETH_OP|11155420|USDC,USDT|sepolia|false"
  "POLYGON|POL|80002|USDC,USDT|amoy|false"
  "SCROLL|ETH_SCROLL|534351|USDC|sepolia|false"
  "SONEIUM|ETH_SONEIUM|1946|USDC_E,USDT|minato|true"
  "UNICHAIN|ETH_UNICHAIN|1301|USDC|sepolia|false"
  "WORLD_CHAIN|ETH_WORLD|4801|USDC|sepolia|true"
)

should_run_chain() {
  local chain=$1
  if [[ -z "${CHAIN_FILTER:-}" ]]; then
    return 0
  fi
  [[ ",${CHAIN_FILTER}," == *",${chain},"* ]]
}

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

if [[ ! -d "$EVM_MATRIX_HARDHAT_ROOT/node_modules" ]]; then
  npm --prefix "$EVM_MATRIX_HARDHAT_ROOT" ci
fi

rsync -a \
  --exclude .git \
  --exclude .codegraph \
  --exclude target \
  --exclude node_modules \
  --exclude artifacts \
  --exclude logs \
  "$EVM_MATRIX_ROOT/" "$EVM_MATRIX_BUILD_ROOT/"
ln -s "$EVM_MATRIX_HARDHAT_ROOT/node_modules" \
  "$EVM_MATRIX_BUILD_ROOT/resources/infra/evm-fork/node_modules"

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

tested_chain_count=0
for definition in "${EVM_CHAINS[@]}"; do
  IFS='|' read -r chain native_symbol chain_id token_symbols network test_eip7702 <<<"$definition"
  should_run_chain "$chain" || continue
  tested_chain_count=$((tested_chain_count + 1))

  local_pg_psql "$EVM_MATRIX_DB" -q -v ON_ERROR_STOP=1 \
    -f "$EVM_MATRIX_BUILD_ROOT/resources/docs/db/surprising-wallet-init-pgsql.sql" \
    >"$EVM_MATRIX_TMP/$chain.schema.log"

  local_pg_psql "$EVM_MATRIX_DB" -q -v ON_ERROR_STOP=1 \
    -v chain="$chain" -v network="$network" -v token_symbols="$token_symbols" <<'SQL'
UPDATE chain_profile
SET enabled = false,
    scan_enabled = false,
    withdraw_enabled = false,
    collection_enabled = false,
    transfer_enabled = false,
    updated_at = now();

UPDATE chain_profile
SET enabled = true,
    scan_enabled = true,
    withdraw_enabled = true,
    collection_enabled = true,
    transfer_enabled = true,
    updated_at = now()
WHERE chain = :'chain'
  AND network = :'network';

UPDATE token_config
SET enabled = (
        chain = :'chain'
        AND symbol = ANY (string_to_array(:'token_symbols', ','))
    ),
    updated_at = now();
SQL

  configured_tokens=$(local_pg_psql "$EVM_MATRIX_DB" -Atqc \
    "select coalesce(string_agg(symbol, ',' order by symbol), '') from token_config where chain='$chain' and enabled=true")
  expected_tokens=$(tr ',' '\n' <<<"$token_symbols" | sed '/^$/d' | sort | paste -sd, -)
  if [[ "$configured_tokens" != "$expected_tokens" ]]; then
    printf '%s token matrix mismatch: database=%s expected=%s\n' \
      "$chain" "$configured_tokens" "$expected_tokens" >&2
    exit 1
  fi

  if [[ "$test_eip7702" == "true" ]]; then
    HARDHAT_CHAIN_ID="$chain_id" HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
      npm --prefix "$EVM_MATRIX_BUILD_ROOT/resources/infra/evm-fork" run test:7702
  fi

  hardhat_log="$EVM_MATRIX_TMP/$chain.hardhat.log"
  (
    cd "$EVM_MATRIX_BUILD_ROOT/resources/infra/evm-fork"
    exec env HARDHAT_CHAIN_ID="$chain_id" HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
      ./node_modules/.bin/hardhat node --hostname 127.0.0.1 --port 8545
  ) >"$hardhat_log" 2>&1 &
  EVM_MATRIX_NODE_PID=$!
  wait_for_rpc "$chain_id" "$hardhat_log"

  EVM_CHAIN="$chain" \
  EVM_NETWORK="$network" \
  TOKEN_SYMBOLS="$token_symbols" \
  PG_URL="$(local_pg_uri "$EVM_MATRIX_DB")" \
  DEPLOYMENT_OUT_DIR="$EVM_MATRIX_TMP/deployments" \
    npm --prefix "$EVM_MATRIX_BUILD_ROOT/resources/infra/evm-fork" run deploy:mock >/dev/null

  mvn -q -f "$EVM_MATRIX_BUILD_ROOT/pom.xml" \
    -pl wallet-service -am \
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

if [[ "$tested_chain_count" == 0 ]]; then
  printf 'CHAIN_FILTER did not match any configured EVM chain: %s\n' "${CHAIN_FILTER:-}" >&2
  exit 1
fi
printf 'EVM local matrix passed for %s chains\n' "$tested_chain_count"
