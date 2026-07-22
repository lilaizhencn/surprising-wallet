#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

DB_TESTS=(
  "com.surprising.wallet.service.chain.solana.SolanaDatabaseFlowIntegrationTest"
  "com.surprising.wallet.service.chain.ton.TonDatabaseFlowIntegrationTest"
  "com.surprising.wallet.service.chain.aptos.AptosDatabaseFlowIntegrationTest"
  "com.surprising.wallet.service.chain.sui.SuiDatabaseFlowIntegrationTest"
  "com.surprising.wallet.service.chain.doge.DogecoinDatabaseFlowIntegrationTest"
  "com.surprising.wallet.service.chain.BitcoinLikeUnifiedUtxoRuntimeMigrationTest"
)
LIVE_CONNECTIVITY_TESTS=(
  "com.surprising.wallet.service.chain.tron.TronNileConnectivityIntegrationTest"
  "com.surprising.wallet.service.chain.ton.TonTestnetConnectivityIntegrationTest"
  "com.surprising.wallet.service.chain.evm.EvmSepoliaLiveScanTest"
  "com.surprising.wallet.service.chain.near.NearTestnetConnectivityIntegrationTest"
  "com.surprising.wallet.service.chain.cardano.CardanoPreprodConnectivityIntegrationTest"
  "com.surprising.wallet.service.chain.polkadot.PolkadotRuntimeConnectivityIntegrationTest"
)
LIVE_SPENDING_TESTS=(
  "com.surprising.wallet.service.chain.tron.TronLiveFullFlowIntegrationTest"
  "com.surprising.wallet.service.chain.solana.SolanaDevnetLiveFlowIntegrationTest"
  "com.surprising.wallet.service.chain.ton.TonLiveMockJettonFlowIntegrationTest"
  "com.surprising.wallet.service.chain.aptos.AptosLiveNativeFlowIntegrationTest"
  "com.surprising.wallet.service.chain.aptos.AptosLiveTokenFlowIntegrationTest"
  "com.surprising.wallet.service.chain.sui.SuiLiveNativeFlowIntegrationTest"
)

usage() {
  cat >&2 <<'USAGE'
usage: scripts/regtest/all-chain-regtest.sh <command>

commands:
  matrix        Show local/fork/live coverage for every supported chain family
  init          Start local UTXO regtest nodes plus XMR wallet-rpc regtest
  status        Show local UTXO status and local EVM fork port status
  stop          Stop local UTXO/XMR regtest nodes
  reset         Reset local UTXO/XMR regtest nodes and volumes
  test-utxo     Run BTC/LTC/DOGE/BCH local regtest flow/concurrency/broadcast tests
  test-xmr      Run XMR local regtest deposit/withdraw/collection integration test
  test-sui      Run isolated local Sui gRPC SUI/USDC full-flow and reconciliation test
  test-solana   Run isolated local Solana SOL/USDT/USDC full-flow and reconciliation test
  test-ton      Run TON/USDT/USDC full flow against an isolated MyLocalTon v2 RPC
  dot-runtime-up    Start the local Polkadot runtime companion service
  dot-runtime-down  Stop the local Polkadot runtime companion service
  test-evm      Run ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C fork regression
  test-db       Run DB-only flow tests for SOL/TON/APTOS/SUI/DOGE plus UTXO migration
  test-live     Run external testnet connectivity checks; spending tests require RUN_LIVE_SPENDING=true
  test-local    Run local UTXO, XMR, EVM, Sui, and Solana full-flow tests
  test-all      Run test-local and test-db; add RUN_LIVE=true to include test-live

notes:
  - BTC/LTC/DOGE/BCH have real local regtest nodes.
  - XMR uses local monerod regtest plus monero-wallet-rpc on 127.0.0.1:18088.
  - EVM chains run as one-at-a-time Hardhat forks on 127.0.0.1:8545.
  - SUI has an isolated local node, gRPC, mock USDC, and PostgreSQL full-flow test.
  - SOLANA has an isolated local validator, mock USDT/USDC, and PostgreSQL full-flow test.
  - TON uses MyLocalTon; start it and fund the deterministic source shown by test-ton.
  - TRON/APTOS/ADA/DOT/NEAR use DB mocks, external testnet/devnet RPC, or a managed runtime service.
  - DOT token tests also need a separate Asset Hub WebSocket RPC configured as purpose=asset_rpc.
USAGE
}

join_by_comma() {
  local IFS=,
  echo "$*"
}

mvn_wallet_test() {
  (cd "${ROOT_DIR}" && mvn -pl backendservices/wallet-parent/wallet-service -am "$@")
}

matrix() {
  cat <<'MATRIX'
chain/family                         environment in repo
BTC/LTC/DOGE/BCH                     local Docker regtest nodes
XMR                                  local monerod regtest + monero-wallet-rpc
ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/
BASE/AVAX_C                          Hardhat fork, one chain per run on 127.0.0.1:8545
TRON                                 external Nile live/testnet flow
SOLANA                               local validator + mock USDT/USDC full flow
TON                                  MyLocalTon + mock USDT/USDC full flow
APTOS                                DB mock + external testnet/devnet live flow
SUI                                  local Sui node + gRPC + mock USDC full flow
ADA                                  external preprod Blockfrost-compatible flow
DOT                                  external Westend/Asset Hub + polkadot-runtime-service
NEAR                                 external testnet JSON-RPC flow
MATRIX
}

init_local_nodes() {
  "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" init
  "${ROOT_DIR}/scripts/regtest/monero-regtest.sh" init
}

status() {
  local dot_runtime_headers=()
  if [[ -n "${POLKADOT_RUNTIME_API_KEY:-}" ]]; then
    dot_runtime_headers=(-H "Authorization: Bearer ${POLKADOT_RUNTIME_API_KEY}")
  fi
  "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" status
  echo "==> xmr:"
  "${ROOT_DIR}/scripts/regtest/monero-regtest.sh" status
  if curl -fsS -m 2 \
      -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
      http://127.0.0.1:8545 >/dev/null 2>&1; then
    echo "==> evm-fork: running on 127.0.0.1:8545"
  else
    echo "==> evm-fork: not running; test-evm starts forks one chain at a time"
  fi
  echo "==> sui: isolated local flow is available through test-sui"
  echo "==> solana: isolated local flow is available through test-solana"
  echo "==> ton: MyLocalTon flow is available through test-ton"
  echo "==> tron/aptos/ada/dot/near: use test-db/test-live or the DOT runtime service"
  if curl -fsS -m 2 \
      "${dot_runtime_headers[@]}" \
      http://127.0.0.1:8787/health >/dev/null 2>&1; then
    echo "==> dot-runtime-service: running on 127.0.0.1:8787"
  else
    echo "==> dot-runtime-service: not running"
  fi
}

dot_runtime_up() {
  : "${POLKADOT_RUNTIME_API_KEY:?set POLKADOT_RUNTIME_API_KEY before starting DOT runtime service}"
  "${ROOT_DIR}/scripts/regtest/polkadot-runtime-service.sh" up
}

dot_runtime_down() {
  "${ROOT_DIR}/scripts/regtest/polkadot-runtime-service.sh" stop
}

test_utxo() {
  "${ROOT_DIR}/scripts/regtest/run-bitcoinlike-matrix.sh"
}

test_evm() {
  (
    cd "${ROOT_DIR}"
    RUN_MULTIUSER="${RUN_MULTIUSER:-true}" \
      "${ROOT_DIR}/evm-fork/scripts/run-fork-regression.sh"
  )
}

test_xmr() {
  "${ROOT_DIR}/scripts/regtest/run-monero-flow.sh"
}

test_sui() {
  "${ROOT_DIR}/scripts/regtest/run-sui-flow.sh"
}

test_solana() {
  "${ROOT_DIR}/scripts/regtest/run-solana-flow.sh"
}

test_ton() {
  "${ROOT_DIR}/scripts/regtest/run-ton-flow.sh"
}

test_db() {
  mvn_wallet_test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsolana.db.enabled=true \
    -Dton.db.enabled=true \
    -Daptos.db.enabled=true \
    -Dsui.db.enabled=true \
    -Ddoge.db.enabled=true \
    -Dutxo.migration.db.enabled=true \
    -Dtest="$(join_by_comma "${DB_TESTS[@]}")" \
    test
}

test_live() {
  local dot_runtime_headers=()
  if [[ -n "${POLKADOT_RUNTIME_API_KEY:-}" ]]; then
    dot_runtime_headers=(-H "Authorization: Bearer ${POLKADOT_RUNTIME_API_KEY}")
  fi
  local cardano_preprod_enabled="${CARDANO_PREPROD_ENABLED:-false}"
  if [[ -n "${BLOCKFROST_PREPROD_PROJECT_ID:-}" ]]; then
    cardano_preprod_enabled=true
  fi
  local polkadot_runtime_enabled="${POLKADOT_RUNTIME_LIVE_ENABLED:-false}"
  if curl -fsS -m 2 "${dot_runtime_headers[@]}" http://127.0.0.1:8787/health >/dev/null 2>&1; then
    polkadot_runtime_enabled=true
  fi
  mvn_wallet_test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtron.live.enabled=true \
    -Dton.testnet.enabled=true \
    -Devm.live.enabled=true \
    -Dnear.testnet.enabled=true \
    -Dcardano.preprod.enabled="${cardano_preprod_enabled}" \
    -Dpolkadot.runtime.live.enabled="${polkadot_runtime_enabled}" \
    -Dtest="$(join_by_comma "${LIVE_CONNECTIVITY_TESTS[@]}")" \
    test

  if [[ "${RUN_LIVE_SPENDING:-false}" == "true" ]]; then
    mvn_wallet_test \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dtron.live.flow.enabled=true \
      -Dsolana.live.enabled=true \
      -Dton.live.enabled=true \
      -Daptos.live.enabled=true \
      -Dsui.live.enabled=true \
      -Dtest="$(join_by_comma "${LIVE_SPENDING_TESTS[@]}")" \
      test
  else
    echo "live spending tests skipped; set RUN_LIVE_SPENDING=true to run funded testnet/devnet spend flows"
  fi
}

case "${1:-matrix}" in
  matrix)
    matrix
    ;;
  init)
    init_local_nodes
    ;;
  status)
    status
    ;;
  stop)
    "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" stop
    "${ROOT_DIR}/scripts/regtest/monero-regtest.sh" stop
    ;;
  reset)
    "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" reset
    "${ROOT_DIR}/scripts/regtest/monero-regtest.sh" reset
    ;;
  test-utxo)
    test_utxo
    ;;
  test-evm)
    test_evm
    ;;
  test-xmr)
    test_xmr
    ;;
  test-sui)
    test_sui
    ;;
  test-solana)
    test_solana
    ;;
  test-ton)
    test_ton
    ;;
  dot-runtime-up)
    dot_runtime_up
    ;;
  dot-runtime-down)
    dot_runtime_down
    ;;
  test-db)
    test_db
    ;;
  test-live)
    test_live
    ;;
  test-local)
    test_utxo
    test_xmr
    test_evm
    test_sui
    test_solana
    ;;
  test-all)
    test_utxo
    test_xmr
    test_evm
    test_sui
    test_solana
    test_db
    if [[ "${RUN_LIVE:-false}" == "true" ]]; then
      test_live
    else
      echo "live tests skipped; set RUN_LIVE=true to include external testnet/devnet checks"
    fi
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 2
    ;;
esac
