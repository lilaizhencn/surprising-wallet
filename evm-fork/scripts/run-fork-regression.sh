#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FORK_DIR="${ROOT_DIR}/evm-fork"
LOG_DIR="${FORK_DIR}/logs"
mkdir -p "${LOG_DIR}"

HARDHAT_PID=""
BLOCKED_CHAINS=()

should_run_chain() {
  local chain="$1"
  if [[ -z "${CHAIN_FILTER:-}" ]]; then
    return 0
  fi
  [[ ",${CHAIN_FILTER}," == *",${chain},"* ]]
}

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
  local deadline=$((SECONDS + ${FORK_START_TIMEOUT_SEC:-120}))
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

latest_block_number() {
  local rpc_url="$1"
  local response
  response="$(curl -fsS -m 8 \
    -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}' \
    "${rpc_url}" 2>/dev/null || true)"
  if [[ -z "${response}" ]]; then
    return 1
  fi
  node -e 'const r=JSON.parse(process.argv[1]); console.log(BigInt(r.result).toString())' "${response}" 2>/dev/null
}

should_pin_latest_block() {
  local chain="$1"
  [[ "${FORK_PIN_LATEST:-true}" == "true" && ( "${chain}" == "POLYGON" || "${chain}" == "BNB" ) ]]
}

start_fork() {
  local chain="$1"
  local rpc_url="$2"
  local expected_chain_id="$3"
  local fork_block="${4:-}"
  local log_file="${LOG_DIR}/${chain}.hardhat.log"
  cleanup
  HARDHAT_PID=""
  (
    cd "${FORK_DIR}"
    fork_args=(--fork "${rpc_url}")
    if [[ -n "${fork_block}" ]]; then
      fork_args+=(--fork-block-number "${fork_block}")
    fi
    if [[ "${chain}" == "POLYGON" || "${FORK_USE_TTY:-false}" == "true" ]]; then
      script -q /dev/null env HARDHAT_CHAIN_ID="${expected_chain_id}" HARDHAT_DISABLE_TELEMETRY_PROMPT=true npx hardhat node \
        "${fork_args[@]}" \
        --hostname 127.0.0.1 \
        --port 8545
    else
      HARDHAT_CHAIN_ID="${expected_chain_id}" HARDHAT_DISABLE_TELEMETRY_PROMPT=true npx hardhat node \
        "${fork_args[@]}" \
        --hostname 127.0.0.1 \
        --port 8545
    fi
  ) >"${log_file}" 2>&1 &
  HARDHAT_PID=$!
  wait_for_rpc "${log_file}" "${expected_chain_id}"
}

run_chain() {
  local chain="$1"
  local native_symbol="$2"
  local expected_chain_id="$3"
  shift 3
  if ! should_run_chain "${chain}"; then
    return 0
  fi
  local rpc_url
  local started=0
  local fork_block_var="${chain}_FORK_BLOCK"
  local fork_block="${!fork_block_var:-}"
  for rpc_url in "$@"; do
    if ! rpc_chain_matches "${rpc_url}" "${expected_chain_id}"; then
      echo "${chain} rpc chainId mismatch or unavailable for ${rpc_url}; trying next endpoint"
      continue
    fi
    local candidate_fork_block="${fork_block}"
    if [[ -z "${candidate_fork_block}" ]] && should_pin_latest_block "${chain}"; then
      candidate_fork_block="$(latest_block_number "${rpc_url}" || true)"
      if [[ -n "${candidate_fork_block}" ]]; then
        echo "${chain} fork block pinned to latest ${candidate_fork_block} for ${rpc_url}"
      fi
    fi
    if start_fork "${chain}" "${rpc_url}" "${expected_chain_id}" "${candidate_fork_block}"; then
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
  ${ETH_RPC_URL:+"${ETH_RPC_URL}"} \
  https://sepolia.gateway.tenderly.co \
  https://sepolia.drpc.org

run_chain BNB BNB 97 \
  ${BNB_RPC_URL:+"${BNB_RPC_URL}"}

run_chain POLYGON MATIC 80002 \
  ${POLYGON_RPC_URL:+"${POLYGON_RPC_URL}"}

run_chain ARBITRUM ETH_ARB 421614 \
  https://sepolia-rollup.arbitrum.io/rpc

run_chain OPTIMISM ETH_OP 11155420 \
  https://sepolia.optimism.io

run_chain BASE ETH_BASE 84532 \
  https://sepolia.base.org

run_chain AVAX_C AVAX_C 43113 \
  https://api.avax-test.network/ext/bc/C/rpc

run_chain HYPEREVM HYPE 998 \
  ${HYPEREVM_RPC_URL:+"${HYPEREVM_RPC_URL}"} \
  https://rpc.hyperliquid-testnet.xyz/evm

if [[ "${#BLOCKED_CHAINS[@]}" -gt 0 ]]; then
  echo "BLOCKED_CHAINS=${BLOCKED_CHAINS[*]}"
  if [[ "${REQUIRE_ALL_EVM_FORKS:-false}" == "true" ]]; then
    exit 1
  fi
fi
