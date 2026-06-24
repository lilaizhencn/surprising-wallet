#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

UTXO_TEST="com.surprising.wallet.service.chain.BitcoinLikeRegtestFullFlowIntegrationTest"
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
)
LIVE_SPENDING_TESTS=(
  "com.surprising.wallet.service.chain.tron.TronLiveFullFlowIntegrationTest"
  "com.surprising.wallet.service.chain.solana.SolanaDevnetLiveFlowIntegrationTest"
  "com.surprising.wallet.service.chain.ton.TonLiveMockJettonFlowIntegrationTest"
  "com.surprising.wallet.service.chain.aptos.AptosLiveNativeFlowIntegrationTest"
  "com.surprising.wallet.service.chain.aptos.AptosLiveTokenFlowIntegrationTest"
  "com.surprising.wallet.service.chain.sui.SuiLiveNativeFlowIntegrationTest"
  "com.surprising.wallet.service.chain.sui.SuiLiveTokenFlowIntegrationTest"
)

usage() {
  cat >&2 <<'USAGE'
usage: scripts/regtest/all-chain-regtest.sh <command>

commands:
  matrix        Show local/fork/live coverage for every supported chain family
  init          Start local UTXO regtest nodes: BTC/LTC/DOGE/BCH
  status        Show local UTXO status and local EVM fork port status
  stop          Stop local UTXO regtest nodes
  reset         Reset local UTXO regtest nodes and volumes
  test-utxo     Run BTC/LTC/DOGE/BCH local regtest flow/concurrency/broadcast tests
  test-evm      Run ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C fork regression
  test-db       Run DB-only flow tests for SOL/TON/APTOS/SUI/DOGE plus UTXO migration
  test-live     Run external testnet connectivity checks; spending tests require RUN_LIVE_SPENDING=true
  test-local    Run test-utxo and test-evm
  test-all      Run test-local and test-db; add RUN_LIVE=true to include test-live

notes:
  - BTC/LTC/DOGE/BCH have real local regtest nodes.
  - EVM chains run as one-at-a-time Hardhat forks on 127.0.0.1:8545.
  - TRON/SOL/TON/APTOS/SUI do not have local node scripts in this repo; their tests use DB mocks or external testnet/devnet RPC.
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
ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/
BASE/AVAX_C                          Hardhat fork, one chain per run on 127.0.0.1:8545
TRON                                 external Nile live/testnet flow
SOLANA                               DB mock + external devnet live flow
TON                                  DB mock + external testnet live flow
APTOS                                DB mock + external testnet/devnet live flow
SUI                                  DB mock + external testnet/devnet live flow
MATRIX
}

init_local_nodes() {
  "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" init
}

status() {
  "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" status
  if curl -fsS -m 2 \
      -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
      http://127.0.0.1:8545 >/dev/null 2>&1; then
    echo "==> evm-fork: running on 127.0.0.1:8545"
  else
    echo "==> evm-fork: not running; test-evm starts forks one chain at a time"
  fi
  echo "==> tron/solana/ton/aptos/sui: no local regtest node scripts; use test-db or test-live"
}

test_utxo() {
  init_local_nodes
  mvn_wallet_test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbitcoinlike.regtest.enabled=true \
    -Dbitcoinlike.concurrency.enabled=true \
    -Dbitcoinlike.broadcast.enabled=true \
    -Dbitcoinlike.broadcast.deposits="${BITCOINLIKE_BROADCAST_DEPOSITS:-40}" \
    -Dbitcoinlike.broadcast.withdrawals="${BITCOINLIKE_BROADCAST_WITHDRAWALS:-20}" \
    -Dtest="${UTXO_TEST}" \
    test
}

test_evm() {
  (
    cd "${ROOT_DIR}"
    RUN_MULTIUSER="${RUN_MULTIUSER:-true}" \
      "${ROOT_DIR}/evm-fork/scripts/run-fork-regression.sh"
  )
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
  mvn_wallet_test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtron.live.enabled=true \
    -Dton.testnet.enabled=true \
    -Devm.live.enabled=true \
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
    ;;
  reset)
    "${ROOT_DIR}/scripts/regtest/bitcoinlike-regtest.sh" reset
    ;;
  test-utxo)
    test_utxo
    ;;
  test-evm)
    test_evm
    ;;
  test-db)
    test_db
    ;;
  test-live)
    test_live
    ;;
  test-local)
    test_utxo
    test_evm
    ;;
  test-all)
    test_utxo
    test_evm
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
