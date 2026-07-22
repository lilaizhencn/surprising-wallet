#!/usr/bin/env bash
set -euo pipefail

UTXO_MATRIX_ROOT=$(git rev-parse --show-toplevel)
source "$UTXO_MATRIX_ROOT/scripts/regtest/local-postgres.sh"
UTXO_MATRIX_TMP=$(mktemp -d -t surprising-utxo-matrix.XXXXXX)
UTXO_MATRIX_DB="surprising_wallet_test_utxo_$$"
UTXO_MATRIX_ACTIVE_CHAIN=""

UTXO_CHAINS=(btc ltc doge bch)

chain_script() {
  case "$1" in
    btc) printf '%s\n' "$UTXO_MATRIX_ROOT/scripts/regtest/bitcoin-regtest.sh" ;;
    ltc) printf '%s\n' "$UTXO_MATRIX_ROOT/scripts/regtest/litecoin-regtest.sh" ;;
    doge) printf '%s\n' "$UTXO_MATRIX_ROOT/scripts/regtest/dogecoin-regtest.sh" ;;
    bch) printf '%s\n' "$UTXO_MATRIX_ROOT/scripts/regtest/bitcoincash-regtest.sh" ;;
  esac
}

chain_code() {
  printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

container_name() {
  case "$1" in
    btc) printf 'surprising-wallet-btc-regtest' ;;
    ltc) printf 'surprising-wallet-ltc-regtest' ;;
    doge) printf 'surprising-wallet-doge-regtest' ;;
    bch) printf 'surprising-wallet-bch-regtest' ;;
  esac
}

stop_chain() {
  local chain=$1
  local container
  container=$(container_name "$chain")
  if docker container inspect "$container" >/dev/null 2>&1 \
      && [[ $(docker inspect -f '{{.State.Running}}' "$container") == true ]]; then
    "$(chain_script "$chain")" stop >/dev/null
  fi
  if [[ "$UTXO_MATRIX_ACTIVE_CHAIN" == "$chain" ]]; then
    UTXO_MATRIX_ACTIVE_CHAIN=""
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$UTXO_MATRIX_ACTIVE_CHAIN" ]]; then
    stop_chain "$UTXO_MATRIX_ACTIVE_CHAIN" || true
  fi
  local_pg_drop "$UTXO_MATRIX_DB" >/dev/null 2>&1 || true
  if [[ "$UTXO_MATRIX_TMP" == *"/surprising-utxo-matrix."* ]] && [[ -d "$UTXO_MATRIX_TMP" ]]; then
    trash "$UTXO_MATRIX_TMP"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in docker git mvn trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
for chain in "${UTXO_CHAINS[@]}"; do
  stop_chain "$chain"
done

local_pg_require
local_pg_create "$UTXO_MATRIX_DB"
UTXO_MATRIX_DB_URL=$(local_pg_jdbc_url "$UTXO_MATRIX_DB")

for chain in "${UTXO_CHAINS[@]}"; do
  code=$(chain_code "$chain")
  local_pg_psql "$UTXO_MATRIX_DB" -q -v ON_ERROR_STOP=1 \
    -f "$UTXO_MATRIX_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
    >"$UTXO_MATRIX_TMP/$code.schema.log"

  "$(chain_script "$chain")" reset
  UTXO_MATRIX_ACTIVE_CHAIN="$chain"
  "$(chain_script "$chain")" init >"$UTXO_MATRIX_TMP/$code.node.log"

  BITCOINLIKE_REGTEST_DB_URL="$UTXO_MATRIX_DB_URL" \
  BITCOINLIKE_REGTEST_DB_USER="$REGTEST_PG_USER" \
  BITCOINLIKE_REGTEST_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
  mvn -q -f "$UTXO_MATRIX_ROOT/pom.xml" \
    -pl backendservices/wallet-parent/wallet-service -am \
    -Dtest=BitcoinLikeRegtestFullFlowIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbitcoinlike.regtest.enabled=true \
    -Dbitcoinlike.concurrency.enabled=true \
    -Dbitcoinlike.broadcast.enabled=true \
    -Dbitcoinlike.regtest.chains="$code" \
    -Dbitcoinlike.broadcast.deposits="${BITCOINLIKE_BROADCAST_DEPOSITS:-8}" \
    -Dbitcoinlike.broadcast.withdrawals="${BITCOINLIKE_BROADCAST_WITHDRAWALS:-4}" \
    test

  negative_balances=$(local_pg_psql "$UTXO_MATRIX_DB" -Atqc \
    "select count(*) from ledger_balance where chain='$code' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)")
  if [[ "$negative_balances" != 0 ]]; then
    printf '%s post-test audit found %s negative balances\n' "$code" "$negative_balances" >&2
    exit 1
  fi

  stop_chain "$chain"
  printf '%s PASS regtest full flow, concurrency, and broadcast\n' "$code"
done

printf 'Bitcoin-like local matrix passed for %s chains\n' "${#UTXO_CHAINS[@]}"
