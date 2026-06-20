#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FORK_DIR="${ROOT_DIR}/evm-fork"
LOG_DIR="${FORK_DIR}/logs"
mkdir -p "${LOG_DIR}"

HARDHAT_PID=""
BLOCKED_CHAINS=()

cleanup() {
  if [[ -n "${HARDHAT_PID}" ]] && kill -0 "${HARDHAT_PID}" 2>/dev/null; then
    kill "${HARDHAT_PID}" 2>/dev/null || true
    wait "${HARDHAT_PID}" 2>/dev/null || true
  fi
  local pids
  pids="$(lsof -ti tcp:8545 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    kill ${pids} 2>/dev/null || true
    sleep 1
  fi
}
trap cleanup EXIT

wait_for_rpc() {
  local log_file="$1"
  local expected_chain_id="$2"
  local deadline=$((SECONDS + 45))
  while (( SECONDS < deadline )); do
    if ! kill -0 "${HARDHAT_PID}" 2>/dev/null; then
      return 1
    fi
    local response
    response="$(curl -fsS -m 2 \
      -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
      http://127.0.0.1:8545 2>/dev/null || true)"
    if [[ -n "${response}" ]]; then
      local actual_chain_id
      actual_chain_id="$(node -e 'const r=JSON.parse(process.argv[1]); console.log(BigInt(r.result).toString())' "${response}" 2>/dev/null || true)"
      if [[ "${actual_chain_id}" == "${expected_chain_id}" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  tail -80 "${log_file}" || true
  return 1
}

rpc_chain_matches() {
  local rpc_url="$1"
  local expected_chain_id="$2"
  local response
  response="$(curl -fsS -m 8 \
    -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    "${rpc_url}" 2>/dev/null || true)"
  if [[ -z "${response}" ]]; then
    return 1
  fi
  local actual_chain_id
  actual_chain_id="$(node -e 'const r=JSON.parse(process.argv[1]); console.log(BigInt(r.result).toString())' "${response}" 2>/dev/null || true)"
  [[ "${actual_chain_id}" == "${expected_chain_id}" ]]
}

start_fork() {
  local chain="$1"
  local rpc_url="$2"
  local expected_chain_id="$3"
  local log_file="${LOG_DIR}/${chain}.hardhat.log"
  cleanup
  HARDHAT_PID=""
  (
    cd "${FORK_DIR}"
    HARDHAT_CHAIN_ID="${expected_chain_id}" HARDHAT_DISABLE_TELEMETRY_PROMPT=true npx hardhat node \
      --fork "${rpc_url}" \
      --hostname 127.0.0.1 \
      --port 8545
  ) >"${log_file}" 2>&1 &
  HARDHAT_PID=$!
  wait_for_rpc "${log_file}" "${expected_chain_id}"
}

run_chain() {
  local chain="$1"
  local native_symbol="$2"
  local expected_chain_id="$3"
  shift 3
  local rpc_url
  local started=0
  for rpc_url in "$@"; do
    if ! rpc_chain_matches "${rpc_url}" "${expected_chain_id}"; then
      echo "${chain} rpc chainId mismatch or unavailable for ${rpc_url}; trying next endpoint"
      continue
    fi
    if start_fork "${chain}" "${rpc_url}" "${expected_chain_id}"; then
      started=1
      echo "${chain} fork started with ${rpc_url}"
      break
    fi
    echo "${chain} fork failed with ${rpc_url}; trying next endpoint"
  done
  if [[ "${started}" != "1" ]]; then
    echo "${chain} BLOCKED: no fork RPC endpoint started successfully"
    BLOCKED_CHAINS+=("${chain}")
    return 0
  fi

  if ! (
    cd "${FORK_DIR}"
    EVM_CHAIN="${chain}" npm run deploy:mock
  ); then
    echo "${chain} BLOCKED: mock ERC20 deployment failed on fork RPC"
    BLOCKED_CHAINS+=("${chain}")
    return 0
  fi

  (
    cd "${ROOT_DIR}"
    mvn -q -pl backendservices/wallet-parent/wallet-service \
      -Dtest=com.surprising.wallet.service.chain.evm.EvmForkFullChainIntegrationTest \
      -Devm.fork.enabled=true \
      -Devm.fork.chain="${chain}" \
      -Devm.native.symbol="${native_symbol}" \
      -Devm.expected.chainId="${expected_chain_id}" \
      -Devm.confirmations=1 \
      test
  )

  if [[ "${RUN_MULTIUSER:-false}" == "true" ]]; then
    (
      cd "${ROOT_DIR}"
      mvn -q -pl backendservices/wallet-parent/wallet-service \
        -Dtest=com.surprising.wallet.service.chain.evm.EvmForkMultiUserBusinessFlowIntegrationTest \
        -Devm.multiuser.enabled=true \
        -Devm.fork.chain="${chain}" \
        -Devm.native.symbol="${native_symbol}" \
        -Devm.expected.chainId="${expected_chain_id}" \
        -Devm.confirmations=1 \
        test
    )
  fi
}

run_chain ETH ETH 11155111 \
  https://ethereum-sepolia-rpc.publicnode.com \
  https://sepolia.drpc.org \
  https://1rpc.io/sepolia

run_chain BNB BNB 97 \
  ${BNB_RPC_URL:+"${BNB_RPC_URL}"} \
  https://bsc-testnet-rpc.publicnode.com \
  https://bsc-testnet-dataseed.bnbchain.org \
  https://bsc-testnet.bnbchain.org \
  https://bsc-prebsc-dataseed.bnbchain.org \
  https://bsc-testnet.drpc.org \
  https://data-seed-prebsc-1-s1.binance.org:8545 \
  https://bsc-testnet.publicnode.com

run_chain POLYGON MATIC 80002 \
  https://polygon-amoy-bor-rpc.publicnode.com \
  https://rpc-amoy.polygon.technology \
  https://polygon-amoy.drpc.org

run_chain ARBITRUM ETH_ARB 421614 \
  https://arbitrum-sepolia-rpc.publicnode.com \
  https://sepolia-rollup.arbitrum.io/rpc \
  https://arbitrum-sepolia.drpc.org

run_chain OPTIMISM ETH_OP 11155420 \
  https://optimism-sepolia-rpc.publicnode.com \
  https://sepolia.optimism.io \
  https://optimism-sepolia.drpc.org

run_chain BASE ETH_BASE 84532 \
  https://base-sepolia-rpc.publicnode.com \
  https://sepolia.base.org \
  https://base-sepolia.drpc.org

run_chain AVAX_C AVAX_C 43113 \
  https://avalanche-fuji-c-chain-rpc.publicnode.com \
  https://api.avax-test.network/ext/bc/C/rpc \
  https://avalanche-fuji-c-chain.drpc.org

if [[ "${#BLOCKED_CHAINS[@]}" -gt 0 ]]; then
  echo "BLOCKED_CHAINS=${BLOCKED_CHAINS[*]}"
fi
