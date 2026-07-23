#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEFAULT_CHAINS=(btc ltc doge bch)

usage() {
  cat >&2 <<'USAGE'
usage: scripts/regtest/bitcoinlike-regtest.sh {start|init|status|mine [blocks]|stop|reset} [btc|ltc|doge|bch...]

examples:
  scripts/regtest/bitcoinlike-regtest.sh init
  scripts/regtest/bitcoinlike-regtest.sh status
  scripts/regtest/bitcoinlike-regtest.sh mine 6 btc ltc
  CHAINS=btc,ltc scripts/regtest/bitcoinlike-regtest.sh init
USAGE
}

script_for_chain() {
  case "$1" in
    btc) echo "${ROOT_DIR}/scripts/regtest/bitcoin-regtest.sh" ;;
    ltc) echo "${ROOT_DIR}/scripts/regtest/litecoin-regtest.sh" ;;
    doge) echo "${ROOT_DIR}/scripts/regtest/dogecoin-regtest.sh" ;;
    bch) echo "${ROOT_DIR}/scripts/regtest/bitcoincash-regtest.sh" ;;
    *)
      echo "unsupported chain: $1" >&2
      usage
      exit 2
      ;;
  esac
}

normalize_chain() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

split_chains_env() {
  local raw=${CHAINS:-}
  if [[ -z "${raw}" ]]; then
    printf '%s\n' "${DEFAULT_CHAINS[@]}"
    return
  fi
  printf '%s' "${raw}" | tr ',' '\n'
}

run_chain() {
  local chain
  chain=$(normalize_chain "$1")
  shift
  local script
  script=$(script_for_chain "${chain}")
  echo "==> ${chain}: $*"
  "${script}" "$@"
}

action=${1:-status}
case "${action}" in
  start|init|status|stop|reset)
    shift || true
    if [[ $# -gt 0 ]]; then
      for chain in "$@"; do
        run_chain "${chain}" "${action}"
      done
    else
      while IFS= read -r chain; do
        [[ -z "${chain}" ]] && continue
        run_chain "${chain}" "${action}"
      done < <(split_chains_env)
    fi
    ;;
  mine)
    shift || true
    blocks=${1:-1}
    if [[ $# -gt 0 ]]; then
      shift
    fi
    if [[ $# -gt 0 ]]; then
      for chain in "$@"; do
        run_chain "${chain}" mine "${blocks}"
      done
    else
      while IFS= read -r chain; do
        [[ -z "${chain}" ]] && continue
        run_chain "${chain}" mine "${blocks}"
      done < <(split_chains_env)
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
