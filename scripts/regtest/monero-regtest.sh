#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LEGACY_IMAGE=${MONERO_REGTEST_IMAGE:-}
DAEMON_IMAGE=${MONERO_REGTEST_DAEMON_IMAGE:-${LEGACY_IMAGE:-ghcr.io/sethforprivacy/simple-monerod:latest}}
WALLET_IMAGE=${MONERO_REGTEST_WALLET_IMAGE:-${LEGACY_IMAGE:-ghcr.io/sethforprivacy/simple-monero-wallet-rpc:latest}}
NETWORK=${MONERO_REGTEST_NETWORK:-surprising-wallet-xmr-regtest}
DAEMON_CONTAINER=${MONERO_REGTEST_DAEMON_CONTAINER:-surprising-wallet-xmr-regtest-daemon}
WALLET_CONTAINER=${MONERO_REGTEST_WALLET_CONTAINER:-surprising-wallet-xmr-regtest-wallet}
FUNDER_CONTAINER=${MONERO_REGTEST_FUNDER_CONTAINER:-surprising-wallet-xmr-regtest-funder}
DAEMON_VOLUME=${MONERO_REGTEST_DAEMON_VOLUME:-surprising-wallet-xmr-regtest-daemon-data}
WALLET_VOLUME=${MONERO_REGTEST_WALLET_VOLUME:-surprising-wallet-xmr-regtest-wallet-data}
FUNDER_VOLUME=${MONERO_REGTEST_FUNDER_VOLUME:-surprising-wallet-xmr-regtest-funder-data}
DAEMON_RPC_PORT=${MONERO_REGTEST_DAEMON_RPC_PORT:-18081}
WALLET_RPC_PORT=${MONERO_REGTEST_WALLET_RPC_PORT:-18088}
FUNDER_RPC_PORT=${MONERO_REGTEST_FUNDER_RPC_PORT:-18090}
BIND_HOST=${MONERO_REGTEST_BIND_HOST:-127.0.0.1}
CLIENT_HOST=${MONERO_REGTEST_CLIENT_HOST:-${BIND_HOST}}
WALLET_RPC_LOGIN=${MONERO_REGTEST_RPC_LOGIN:-}
WALLET_FILE=${MONERO_REGTEST_WALLET_FILE:-surprising-wallet-regtest}
FUNDER_WALLET_FILE=${MONERO_REGTEST_FUNDER_WALLET_FILE:-surprising-wallet-regtest-funder}
WALLET_PASSWORD=${MONERO_REGTEST_WALLET_PASSWORD:-wallet123}
WALLET_LANGUAGE=${MONERO_REGTEST_WALLET_LANGUAGE:-English}
CHAIN_MODE=${MONERO_REGTEST_CHAIN_MODE:-regtest}
FAUCET_AMOUNT=${MONERO_REGTEST_FAUCET_AMOUNT:-1}
WAIT_SECONDS=${MONERO_REGTEST_WAIT_SECONDS:-90}
DOCKER_BIN=${DOCKER_BIN:-docker}
DOCKER_PREFIX=()
if [[ "${DOCKER_USE_SUDO:-false}" == "true" ]]; then
  DOCKER_PREFIX=(sudo)
fi

docker_cmd() {
  "${DOCKER_PREFIX[@]}" "${DOCKER_BIN}" "$@"
}

require_command() {
  local name=$1
  if ! command -v "${name}" >/dev/null 2>&1; then
    echo "missing required command: ${name}" >&2
    exit 127
  fi
}

require_runtime_tools() {
  require_command curl
  require_command python3
}

require_docker() {
  require_command "${DOCKER_BIN}"
  if ! docker_cmd version >/dev/null 2>&1; then
    echo "Docker is required for XMR regtest but is not reachable. Start Docker or set DOCKER_BIN/DOCKER_USE_SUDO." >&2
    exit 125
  fi
}

DAEMON_NETWORK_ARGS=()
WALLET_NETWORK_ARGS=()
case "${CHAIN_MODE}" in
  regtest)
    DAEMON_NETWORK_ARGS=(--regtest --offline --fixed-difficulty 1)
    WALLET_NETWORK_ARGS=(--allow-mismatched-daemon-version)
    ;;
  local-testnet|testnet)
    DAEMON_NETWORK_ARGS=(--testnet --offline --fixed-difficulty 1)
    WALLET_NETWORK_ARGS=(--testnet)
    ;;
  *)
    echo "unsupported MONERO_REGTEST_CHAIN_MODE=${CHAIN_MODE}; use local-testnet or regtest" >&2
    exit 2
    ;;
esac

json_rpc() {
  local url=$1
  local method=$2
  local params=${3:-{}}
  local login=${4:-}
  local response
  local curl_args=(-fsS -H 'content-type: application/json')
  if [[ -n "${login}" ]]; then
    curl_args+=(--digest -u "${login}")
  fi
  response=$(curl \
    "${curl_args[@]}" \
    --data "{\"jsonrpc\":\"2.0\",\"id\":\"0\",\"method\":\"${method}\",\"params\":${params}}" \
    "${url}/json_rpc")
  printf '%s\n' "${response}" | python3 -c 'import json,sys; data=json.load(sys.stdin); err=data.get("error");
if err:
    print(json.dumps(data, indent=2), file=sys.stderr)
    sys.exit(1)
print(json.dumps(data, indent=2))'
}

daemon_rpc() {
  local method=${1:?}
  local params=${2:-{}}
  json_rpc "http://${CLIENT_HOST}:${DAEMON_RPC_PORT}" "${method}" "${params}"
}

wallet_rpc() {
  local method=${1:?}
  local params=${2:-{}}
  json_rpc "http://${CLIENT_HOST}:${WALLET_RPC_PORT}" "${method}" "${params}" "${WALLET_RPC_LOGIN}"
}

funder_rpc() {
  local method=${1:?}
  local params=${2:-{}}
  json_rpc "http://${CLIENT_HOST}:${FUNDER_RPC_PORT}" "${method}" "${params}" "${WALLET_RPC_LOGIN}"
}

json_value() {
  python3 -c 'import json,sys; data=json.load(sys.stdin); path=sys.argv[1].split("."); value=data; [value:=value[p] for p in path]; print(value)' "$1"
}

wait_daemon() {
  for _ in $(seq 1 "${WAIT_SECONDS}"); do
    if daemon_rpc get_block_count >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "XMR regtest daemon is not reachable at http://${CLIENT_HOST}:${DAEMON_RPC_PORT}." >&2
  echo "If containers are bound to a private IP, set MONERO_REGTEST_CLIENT_HOST to that IP." >&2
  echo "Current bind/client hosts: MONERO_REGTEST_BIND_HOST=${BIND_HOST}, MONERO_REGTEST_CLIENT_HOST=${CLIENT_HOST}." >&2
  docker_cmd logs "${DAEMON_CONTAINER}" >&2 || true
  return 1
}

wait_wallet_rpc() {
  local port=$1
  local container=$2
  for _ in $(seq 1 "${WAIT_SECONDS}"); do
    if json_rpc "http://${CLIENT_HOST}:${port}" get_version '{}' "${WALLET_RPC_LOGIN}" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "XMR wallet-rpc container ${container} is not reachable at http://${CLIENT_HOST}:${port}." >&2
  echo "If RPC auth is enabled, set MONERO_REGTEST_RPC_LOGIN=user:password." >&2
  echo "If containers are bound to a private IP, set MONERO_REGTEST_CLIENT_HOST to that IP." >&2
  echo "Current bind/client hosts: MONERO_REGTEST_BIND_HOST=${BIND_HOST}, MONERO_REGTEST_CLIENT_HOST=${CLIENT_HOST}." >&2
  docker_cmd logs "${container}" >&2 || true
  return 1
}

ensure_network() {
  require_docker
  docker_cmd network inspect "${NETWORK}" >/dev/null 2>&1 || docker_cmd network create "${NETWORK}" >/dev/null
}

start_daemon() {
  ensure_network
  if docker_cmd container inspect "${DAEMON_CONTAINER}" >/dev/null 2>&1; then
    docker_cmd start "${DAEMON_CONTAINER}" >/dev/null
  else
    docker_cmd run -d \
      --name "${DAEMON_CONTAINER}" \
      --network "${NETWORK}" \
      -p "${BIND_HOST}:${DAEMON_RPC_PORT}:18081" \
      -v "${DAEMON_VOLUME}:/home/monero/.bitmonero" \
      "${DAEMON_IMAGE}" \
      "${DAEMON_NETWORK_ARGS[@]}" \
      --rpc-bind-ip 0.0.0.0 \
      --rpc-bind-port 18081 \
      --confirm-external-bind >/dev/null
  fi
  wait_daemon
}

start_wallet_rpc_container() {
  local container=$1
  local volume=$2
  local host_port=$3
  local rpc_login_args=()
  if [[ -n "${WALLET_RPC_LOGIN}" ]]; then
    rpc_login_args=(--rpc-login "${WALLET_RPC_LOGIN}")
  else
    rpc_login_args=(--disable-rpc-login)
  fi
  start_daemon
  if docker_cmd container inspect "${container}" >/dev/null 2>&1; then
    docker_cmd start "${container}" >/dev/null
  else
    docker_cmd run -d \
      --name "${container}" \
      --network "${NETWORK}" \
      -p "${BIND_HOST}:${host_port}:18088" \
      -v "${volume}:/home/monero/wallet" \
      "${WALLET_IMAGE}" \
      "${WALLET_NETWORK_ARGS[@]}" \
      --wallet-dir /home/monero/wallet \
      --daemon-address "http://${DAEMON_CONTAINER}:18081" \
      --trusted-daemon \
      --rpc-bind-port 18088 \
      "${rpc_login_args[@]}" >/dev/null
  fi
  wait_wallet_rpc "${host_port}" "${container}"
}

start_wallet_rpc() {
  start_wallet_rpc_container "${WALLET_CONTAINER}" "${WALLET_VOLUME}" "${WALLET_RPC_PORT}"
}

start_funder_rpc() {
  start_wallet_rpc_container "${FUNDER_CONTAINER}" "${FUNDER_VOLUME}" "${FUNDER_RPC_PORT}"
}

ensure_wallet_at() {
  local port=$1
  local file=$2
  if ! json_rpc "http://${CLIENT_HOST}:${port}" create_wallet \
      "{\"filename\":\"${file}\",\"password\":\"${WALLET_PASSWORD}\",\"language\":\"${WALLET_LANGUAGE}\"}" \
      "${WALLET_RPC_LOGIN}" >/dev/null 2>&1; then
    json_rpc "http://${CLIENT_HOST}:${port}" open_wallet \
      "{\"filename\":\"${file}\",\"password\":\"${WALLET_PASSWORD}\"}" \
      "${WALLET_RPC_LOGIN}" >/dev/null
  fi
}

ensure_wallet() {
  start_wallet_rpc
  ensure_wallet_at "${WALLET_RPC_PORT}" "${WALLET_FILE}"
}

ensure_funder_wallet() {
  start_funder_rpc
  ensure_wallet_at "${FUNDER_RPC_PORT}" "${FUNDER_WALLET_FILE}"
}

primary_address() {
  ensure_wallet
  wallet_rpc get_address '{"account_index":0}' | json_value result.address
}

funder_address() {
  ensure_funder_wallet
  funder_rpc get_address '{"account_index":0}' | json_value result.address
}

refresh_wallet() {
  wallet_rpc refresh '{}' >/dev/null || true
  funder_rpc refresh '{}' >/dev/null || true
}

mine() {
  ensure_funder_wallet
  local blocks=${1:-1}
  local address=${2:-}
  if [[ -z "${address}" ]]; then
    address=$(funder_address)
  fi
  daemon_rpc generateblocks "{\"amount_of_blocks\":${blocks},\"wallet_address\":\"${address}\",\"starting_nonce\":0}"
  refresh_wallet
}

init_funder() {
  ensure_wallet
  ensure_funder_wallet
  local height
  height=$(daemon_rpc get_block_count | json_value result.count)
  if (( height < 80 )); then
    mine $((80 - height)) "$(funder_address)" >/dev/null
  else
    refresh_wallet
  fi
  funder_rpc get_balance '{"account_index":0}'
}

to_atomic() {
  python3 -c 'from decimal import Decimal; import sys; print(int((Decimal(sys.argv[1]) * (Decimal(10) ** 12)).to_integral_exact()))' "$1"
}

fund_address() {
  local target=$1
  local amount=${2:-${FAUCET_AMOUNT}}
  local atomic
  atomic=$(to_atomic "${amount}")
  ensure_wallet
  ensure_funder_wallet
  local tx
  tx=$(funder_rpc transfer "{\"destinations\":[{\"address\":\"${target}\",\"amount\":${atomic}}],\"account_index\":0,\"subaddr_indices\":[0],\"priority\":0,\"ring_size\":16,\"unlock_time\":0,\"get_tx_key\":true}")
  mine 2 "$(funder_address)" >/dev/null
  printf '%s\n' "${tx}"
}

self_test() {
  init_funder >/dev/null
  local before created address index funded transfer_count
  before=$(daemon_rpc get_block_count | json_value result.count)
  before=$((before > 0 ? before - 1 : 0))
  created=$(wallet_rpc create_address '{"account_index":0,"label":"surprising-wallet self-test"}')
  address=$(printf '%s\n' "${created}" | json_value result.address)
  index=$(printf '%s\n' "${created}" | json_value result.address_index)
  funded=$(fund_address "${address}" "0.1")
  transfer_count=$(wallet_rpc get_transfers "{\"in\":true,\"pool\":false,\"pending\":false,\"failed\":false,\"account_index\":0,\"filter_by_height\":true,\"min_height\":${before}}" \
    | python3 -c 'import json,sys; data=json.load(sys.stdin).get("result",{}); idx=int(sys.argv[1]); print(sum(1 for item in data.get("in",[]) if item.get("subaddr_index",{}).get("minor")==idx))' "${index}")
  if [[ "${transfer_count}" -lt 1 ]]; then
    echo "XMR regtest self-test did not observe incoming transfer for subaddress index ${index}" >&2
    echo "${funded}" >&2
    return 1
  fi
  printf 'XMR regtest self-test OK: address_index=%s address=%s incoming_transfers=%s\n' \
    "${index}" "${address}" "${transfer_count}"
}

status() {
  require_runtime_tools
  require_docker
  wait_daemon
  wait_wallet_rpc "${WALLET_RPC_PORT}" "${WALLET_CONTAINER}"
  daemon_rpc get_block_count
  wallet_rpc get_balance '{"account_index":0}' || true
  if docker_cmd container inspect "${FUNDER_CONTAINER}" >/dev/null 2>&1; then
    wait_wallet_rpc "${FUNDER_RPC_PORT}" "${FUNDER_CONTAINER}" || true
    funder_rpc get_balance '{"account_index":0}' || true
  fi
}

stop() {
  require_docker
  docker_cmd stop "${WALLET_CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd stop "${FUNDER_CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd stop "${DAEMON_CONTAINER}" >/dev/null 2>&1 || true
}

reset() {
  require_docker
  docker_cmd rm -f "${WALLET_CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd rm -f "${FUNDER_CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd rm -f "${DAEMON_CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd volume rm "${WALLET_VOLUME}" >/dev/null 2>&1 || true
  docker_cmd volume rm "${FUNDER_VOLUME}" >/dev/null 2>&1 || true
  docker_cmd volume rm "${DAEMON_VOLUME}" >/dev/null 2>&1 || true
  docker_cmd network rm "${NETWORK}" >/dev/null 2>&1 || true
}

case "${1:-status}" in
  start)
    require_runtime_tools
    start_wallet_rpc
    ;;
  init)
    require_runtime_tools
    init_funder
    ;;
  status)
    status
    ;;
  address)
    require_runtime_tools
    primary_address
    ;;
  daemon)
    shift
    require_runtime_tools
    daemon_rpc "$@"
    ;;
  wallet)
    shift
    require_runtime_tools
    wallet_rpc "$@"
    ;;
  mine)
    shift
    require_runtime_tools
    mine "${1:-1}" "${2:-}"
    ;;
  fund)
    shift
    require_runtime_tools
    target=${1:?usage: $0 fund <address> [amount_xmr]}
    amount=${2:-${FAUCET_AMOUNT}}
    fund_address "${target}" "${amount}"
    ;;
  self-test)
    require_runtime_tools
    self_test
    ;;
  stop)
    stop
    ;;
  reset)
    reset
    ;;
  *)
    echo "usage: $0 {start|init|status|address|daemon <method> [json]|wallet <method> [json]|mine [blocks] [address]|fund <address> [amount_xmr]|self-test|stop|reset}" >&2
    exit 2
    ;;
esac
