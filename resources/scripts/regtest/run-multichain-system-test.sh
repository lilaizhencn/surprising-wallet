#!/usr/bin/env bash
set -euo pipefail

SYSTEM_ROOT=$(git rev-parse --show-toplevel)
SYSTEM_LOG_DIR=$(mktemp -d /tmp/surprising-wallet-multichain-system.XXXXXX)
SYSTEM_TRON_HTTP_PORT=${TRON_FLOW_HTTP_PORT:-19090}
declare -a SYSTEM_NAMES=()
declare -a SYSTEM_PIDS=()

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  for pid in "${SYSTEM_PIDS[@]}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  wait >/dev/null 2>&1 || true
  if [[ "$status" != 0 && "${MULTICHAIN_KEEP_LOGS:-false}" == true ]]; then
    printf 'multi-chain system logs kept at %s\n' "$SYSTEM_LOG_DIR" >&2
    exit "$status"
  fi
  if [[ "$SYSTEM_LOG_DIR" == /tmp/surprising-wallet-multichain-system.* ]]; then
    rm -rf "$SYSTEM_LOG_DIR"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

start_job() {
  local name=$1
  shift
  SYSTEM_NAMES+=("$name")
  ("$@") >"$SYSTEM_LOG_DIR/$name.log" 2>&1 &
  SYSTEM_PIDS+=("$!")
  printf 'started %-9s pid=%s\n' "$name" "$!"
}

wait_for_tron_ready() {
  local tron_pid=${SYSTEM_PIDS[0]}
  local accounts_json=''
  for attempt in $(seq 1 300); do
    accounts_json=$(curl -fsS --max-time 2 \
      "http://127.0.0.1:${SYSTEM_TRON_HTTP_PORT}/admin/accounts-json" 2>/dev/null || true)
    if jq -e '.privateKeys | length >= 1' >/dev/null 2>&1 <<<"$accounts_json"; then
      printf 'ready   %-9s\n' tron
      return 0
    fi
    if ! kill -0 "$tron_pid" >/dev/null 2>&1; then
      printf 'TRON flow exited before its local FullNode became ready\n' >&2
      tail -160 "$SYSTEM_LOG_DIR/tron.log" >&2 || true
      return 1
    fi
    sleep 1
  done
  printf 'TRON local FullNode did not become ready within 5 minutes\n' >&2
  tail -160 "$SYSTEM_LOG_DIR/tron.log" >&2 || true
  return 1
}

start_job tron "$SYSTEM_ROOT/scripts/regtest/run-tron-flow.sh"
wait_for_tron_ready
start_job near "$SYSTEM_ROOT/scripts/regtest/run-near-flow.sh"
start_job cardano "$SYSTEM_ROOT/scripts/regtest/run-cardano-flow.sh"
start_job load "$SYSTEM_ROOT/scripts/regtest/run-multichain-load.sh"

failed=0
for index in "${!SYSTEM_PIDS[@]}"; do
  name=${SYSTEM_NAMES[$index]}
  pid=${SYSTEM_PIDS[$index]}
  if wait "$pid"; then
    printf 'passed  %-9s\n' "$name"
  else
    printf 'failed  %-9s\n' "$name" >&2
    tail -160 "$SYSTEM_LOG_DIR/$name.log" >&2 || true
    failed=1
  fi
done

if [[ "$failed" != 0 ]]; then
  exit 1
fi

for name in "${SYSTEM_NAMES[@]}"; do
  printf '\n==> %s summary\n' "$name"
  tail -20 "$SYSTEM_LOG_DIR/$name.log"
done
printf '\nMULTI-CHAIN SYSTEM PASS: TRON, NEAR, and Cardano ran with the 1000-user custody load\n'
