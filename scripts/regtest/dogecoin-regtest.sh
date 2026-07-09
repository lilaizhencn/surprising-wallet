#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
IMAGE=${DOGE_REGTEST_IMAGE:-surprising-wallet/dogecoin-core:1.14.9}
CONTAINER=${DOGE_REGTEST_CONTAINER:-surprising-wallet-doge-regtest}
VOLUME=${DOGE_REGTEST_VOLUME:-surprising-wallet-doge-regtest-data}
RPC_USER=${DOGE_REGTEST_RPC_USER:-wallet}
RPC_PASSWORD=${DOGE_REGTEST_RPC_PASSWORD:-wallet123}
RPC_PORT=${DOGE_REGTEST_RPC_PORT:-22555}

cli() {
  docker exec "${CONTAINER}" dogecoin-cli \
    -regtest \
    -rpcuser="${RPC_USER}" \
    -rpcpassword="${RPC_PASSWORD}" \
    -rpcport=22555 \
    "$@"
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
    "${ROOT_DIR}/infra/regtest/dogecoin"
  if docker container inspect "${CONTAINER}" >/dev/null 2>&1; then
    docker start "${CONTAINER}" >/dev/null
  else
    docker run -d \
      --name "${CONTAINER}" \
      --platform linux/amd64 \
      -p "127.0.0.1:${RPC_PORT}:22556" \
      -e DOGE_REGTEST_RPC_USER="${RPC_USER}" \
      -e DOGE_REGTEST_RPC_PASSWORD="${RPC_PASSWORD}" \
      -v "${VOLUME}:/var/lib/dogecoin" \
      "${IMAGE}" >/dev/null
  fi
  wait_ready
  cli getblockchaininfo
}

init_funder() {
  wait_ready
  local height address blocks
  height=$(cli getblockcount)
  if (( height < 101 )); then
    address=$(cli getnewaddress)
    blocks=$((101 - height))
    cli generatetoaddress "${blocks}" "${address}" >/dev/null
  fi
  cli getwalletinfo
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
  mine)
    shift
    blocks=${1:-1}
    address=$(cli getnewaddress)
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
    echo "usage: $0 {start|init|status|cli <args...>|mine [blocks]|stop|reset}" >&2
    exit 2
    ;;
esac
