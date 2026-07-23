#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
IMAGE=${BCH_REGTEST_IMAGE:-surprising-wallet/bitcoincash-node:29.0.0}
CONTAINER=${BCH_REGTEST_CONTAINER:-surprising-wallet-bch-regtest}
VOLUME=${BCH_REGTEST_VOLUME:-surprising-wallet-bch-regtest-data}
RPC_USER=${BCH_REGTEST_RPC_USER:-wallet}
RPC_PASSWORD=${BCH_REGTEST_RPC_PASSWORD:-wallet123}
RPC_PORT=${BCH_REGTEST_RPC_PORT:-18443}
WALLET=${BCH_REGTEST_WALLET:-regtest-funder}

cli() {
  docker exec "${CONTAINER}" bitcoin-cli \
    -regtest \
    -rpcuser="${RPC_USER}" \
    -rpcpassword="${RPC_PASSWORD}" \
    -rpcport=18443 \
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
    "${ROOT_DIR}/infra/regtest/bitcoincash"
  if docker container inspect "${CONTAINER}" >/dev/null 2>&1; then
    docker start "${CONTAINER}" >/dev/null
  else
    docker run -d \
      --name "${CONTAINER}" \
      --platform linux/amd64 \
      -p "127.0.0.1:${RPC_PORT}:18445" \
      -e BCH_REGTEST_RPC_USER="${RPC_USER}" \
      -e BCH_REGTEST_RPC_PASSWORD="${RPC_PASSWORD}" \
      -v "${VOLUME}:/var/lib/bitcoincash" \
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
