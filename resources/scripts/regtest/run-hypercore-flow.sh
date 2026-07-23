#!/usr/bin/env bash
set -euo pipefail

HYPERCORE_ROOT=$(git rev-parse --show-toplevel)
source "$HYPERCORE_ROOT/scripts/regtest/local-postgres.sh"
HYPERCORE_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-hypercore.XXXXXX)
HYPERCORE_DB="surprising_wallet_test_hypercore_$$"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$HYPERCORE_DB" >/dev/null 2>&1 || true
  if [[ "$HYPERCORE_BUILD_ROOT" == /tmp/surprising-wallet-hypercore.* ]]; then
    rm -rf "$HYPERCORE_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl git jq mvn rsync; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require

printf 'checking official HyperCore testnet metadata\n'
metadata=$(curl -fsS --max-time 30 \
  -H 'content-type: application/json' \
  --data '{"type":"spotMeta"}' \
  https://api.hyperliquid-testnet.xyz/info)
jq -e '([.tokens[].name] | index("USDC") != null)
       and ([.tokens[].name] | index("HYPE") != null)' >/dev/null <<<"$metadata"

local_pg_create "$HYPERCORE_DB"
local_pg_psql "$HYPERCORE_DB" -q -v ON_ERROR_STOP=1 \
  -f "$HYPERCORE_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$HYPERCORE_DB" -q -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     'hypercore-local-test');
SQL

rsync -a \
  --exclude=.git \
  --exclude=.codegraph \
  --exclude=target \
  --exclude=node_modules \
  --exclude=logs \
  "$HYPERCORE_ROOT/" "$HYPERCORE_BUILD_ROOT/"

HYPERCORE_DB_URL="$(local_pg_jdbc_url "$HYPERCORE_DB")" \
HYPERCORE_DB_USER="$REGTEST_PG_USER" \
HYPERCORE_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
mvn -f "$HYPERCORE_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=HyperCoreLocalFullFlowIntegrationTest,HyperCoreSignerTest \
  -Dhypercore.local.flow.enabled=true \
  test

negative_balances=$(local_pg_psql "$HYPERCORE_DB" -Atqc \
  "select count(*) from ledger_balance
    where available_balance < 0 or locked_balance < 0 or total_balance < 0")
nonterminal_orders=$(local_pg_psql "$HYPERCORE_DB" -Atqc \
  "select count(*) from withdrawal_order
    where chain='HYPERCORE' and status not in ('CONFIRMED','FAILED','CANCELLED')")
nonterminal_collections=$(local_pg_psql "$HYPERCORE_DB" -Atqc \
  "select count(*) from collection_record
    where chain='HYPERCORE' and status <> 'CONFIRMED'")
failed_actions=$(local_pg_psql "$HYPERCORE_DB" -Atqc \
  "select count(*) from hypercore_action_record where status <> 'ACCEPTED'")
if [[ "$negative_balances" != 0 || "$nonterminal_orders" != 0 \
      || "$nonterminal_collections" != 0 || "$failed_actions" != 0 ]]; then
  printf 'HyperCore audit failed: negative=%s orders=%s collections=%s actions=%s\n' \
    "$negative_balances" "$nonterminal_orders" "$nonterminal_collections" "$failed_actions" >&2
  exit 1
fi

printf 'HYPERCORE PASS: testnet metadata + USDC/HYPE deposit/replay/withdraw/collection/concurrency/audit\n'
