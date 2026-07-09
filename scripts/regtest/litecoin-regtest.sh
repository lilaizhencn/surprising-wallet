#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
IMAGE=${LTC_REGTEST_IMAGE:-surprising-wallet/litecoin-core:0.21.4}
CONTAINER=${LTC_REGTEST_CONTAINER:-surprising-wallet-ltc-regtest}
VOLUME=${LTC_REGTEST_VOLUME:-surprising-wallet-ltc-regtest-data}
RPC_USER=${LTC_REGTEST_RPC_USER:-wallet}
RPC_PASSWORD=${LTC_REGTEST_RPC_PASSWORD:-wallet123}
RPC_PORT=${LTC_REGTEST_RPC_PORT:-19443}
WALLET=${LTC_REGTEST_WALLET:-regtest-funder}

cli() {
  docker exec "${CONTAINER}" litecoin-cli \
    -regtest \
    -rpcuser="${RPC_USER}" \
    -rpcpassword="${RPC_PASSWORD}" \
    -rpcport=19443 \
    "$@"
}

wallet_cli() {
  cli -rpcwallet="${WALLET}" "$@"
}

wait_ready() {
  for _ in $(seq 1 60); do
    if cli getblockchaininfo >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  docker logs "${CONTAINER}" >&2
  return 1
}

start() {
  docker info >/dev/null
  docker build --platform linux/amd64 \
    -t "${IMAGE}" \
    "${ROOT_DIR}/infra/regtest/litecoin"
  if docker container inspect "${CONTAINER}" >/dev/null 2>&1; then
    docker start "${CONTAINER}" >/dev/null
  else
    docker run -d \
      --name "${CONTAINER}" \
      --platform linux/amd64 \
      -p "127.0.0.1:${RPC_PORT}:19445" \
      -e LTC_REGTEST_RPC_USER="${RPC_USER}" \
      -e LTC_REGTEST_RPC_PASSWORD="${RPC_PASSWORD}" \
      -v "${VOLUME}:/var/lib/litecoin" \
      "${IMAGE}" >/dev/null
  fi
  wait_ready
  cli getblockchaininfo
}

init_funder() {
  wait_ready
  if ! cli listwalletdir | jq -e --arg wallet "${WALLET}" \
      '.wallets[]? | select(.name == $wallet)' >/dev/null; then
    cli createwallet "${WALLET}" >/dev/null
  elif ! cli listwallets | jq -e --arg wallet "${WALLET}" \
      '.[] | select(. == $wallet)' >/dev/null; then
    cli loadwallet "${WALLET}" >/dev/null
  fi
  local height address blocks
  height=$(cli getblockcount)
  if (( height < 101 )); then
    address=$(wallet_cli getnewaddress)
    blocks=$((101 - height))
    cli generatetoaddress "${blocks}" "${address}" >/dev/null
  fi
  wallet_cli getwalletinfo
}

case "${1:-status}" in
  start)
    start
    ;;
  init)
    start
    init_funder
    ;;
  status)
    wait_ready
    cli getblockchaininfo
    ;;
  cli)
    shift
    cli "$@"
    ;;
  wallet-cli)
    shift
    wallet_cli "$@"
    ;;
  mine)
    shift
    blocks=${1:-1}
    address=$(wallet_cli getnewaddress)
    cli generatetoaddress "${blocks}" "${address}"
    ;;
  stop)
    docker stop "${CONTAINER}"
    ;;
  reset)
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
    docker volume rm "${VOLUME}" >/dev/null 2>&1 || true
    ;;
  *)
    echo "usage: $0 {start|init|status|cli <args...>|wallet-cli <args...>|mine [blocks]|stop|reset}" >&2
    exit 2
    ;;
esac
