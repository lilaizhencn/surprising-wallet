#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SERVICE_DIR="${ROOT_DIR}/infra/polkadot-runtime-service"
IMAGE="${POLKADOT_RUNTIME_IMAGE:-surprising-wallet/polkadot-runtime-service:latest}"
CONTAINER="${POLKADOT_RUNTIME_CONTAINER:-surprising-wallet-polkadot-runtime}"
BIND_HOST="${POLKADOT_RUNTIME_BIND_HOST:-127.0.0.1}"
PORT="${POLKADOT_RUNTIME_PORT:-8787}"

DOCKER_BIN="${DOCKER_BIN:-docker}"
DOCKER_PREFIX=()
if [[ "${DOCKER_USE_SUDO:-false}" == "true" ]]; then
  DOCKER_PREFIX=(sudo)
fi

usage() {
  cat >&2 <<'USAGE'
usage: scripts/regtest/polkadot-runtime-service.sh <command>

commands:
  build       Build the Polkadot runtime service Docker image
  up          Build and start the service on POLKADOT_RUNTIME_BIND_HOST:POLKADOT_RUNTIME_PORT
  stop        Stop and remove the service container
  status      Show container and health status
  logs        Tail service logs

env:
  POLKADOT_RUNTIME_API_KEY     Required for up
  POLKADOT_RUNTIME_BIND_HOST   Bind host, default 127.0.0.1
  POLKADOT_RUNTIME_PORT        Port, default 8787
  DOCKER_BIN                   Docker binary, default docker
  DOCKER_USE_SUDO              Set true when Docker requires sudo
USAGE
}

docker_cmd() {
  if [[ ${#DOCKER_PREFIX[@]} -gt 0 ]]; then
    "${DOCKER_PREFIX[@]}" "${DOCKER_BIN}" "$@"
    return
  fi
  "${DOCKER_BIN}" "$@"
}

build() {
  docker_cmd build -t "${IMAGE}" "${SERVICE_DIR}"
}

up() {
  : "${POLKADOT_RUNTIME_API_KEY:?set POLKADOT_RUNTIME_API_KEY before starting DOT runtime service}"
  build
  docker_cmd rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  docker_cmd run -d \
    --name "${CONTAINER}" \
    --restart unless-stopped \
    -e POLKADOT_RUNTIME_API_KEY="${POLKADOT_RUNTIME_API_KEY}" \
    -e POLKADOT_RUNTIME_DEV_MODE="${POLKADOT_RUNTIME_DEV_MODE:-false}" \
    -e POLKADOT_RUNTIME_HOST=0.0.0.0 \
    -e POLKADOT_RUNTIME_PORT="${PORT}" \
    -p "${BIND_HOST}:${PORT}:${PORT}" \
    "${IMAGE}" >/dev/null
  status
}

stop() {
  docker_cmd rm -f "${CONTAINER}" >/dev/null 2>&1 || true
}

status() {
  docker_cmd ps --filter "name=${CONTAINER}"
  local headers=()
  if [[ -n "${POLKADOT_RUNTIME_API_KEY:-}" ]]; then
    headers=(-H "Authorization: Bearer ${POLKADOT_RUNTIME_API_KEY}")
  fi
  if curl -fsS -m 2 "${headers[@]}" "http://${BIND_HOST}:${PORT}/health" >/dev/null 2>&1; then
    echo "polkadot-runtime-service health: ok"
  else
    echo "polkadot-runtime-service health: unavailable"
  fi
}

logs() {
  docker_cmd logs -f --tail 100 "${CONTAINER}"
}

case "${1:-status}" in
  build)
    build
    ;;
  up)
    up
    ;;
  stop|down)
    stop
    ;;
  status)
    status
    ;;
  logs)
    logs
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 2
    ;;
esac
