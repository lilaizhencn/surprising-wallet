#!/usr/bin/env bash
set -euo pipefail

SOLANA_FLOW_ROOT=$(git rev-parse --show-toplevel)
source "$SOLANA_FLOW_ROOT/scripts/regtest/local-postgres.sh"
SOLANA_FLOW_VALIDATOR="surprising-wallet-solana-flow-$$"
SOLANA_FLOW_DB="surprising_wallet_test_solana_$$"
SOLANA_FLOW_RPC_PORT=${SOLANA_FLOW_RPC_PORT:-18899}
SOLANA_TEST_MASTER_SEED=${SOLANA_TEST_MASTER_SEED:-000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  docker rm -f "$SOLANA_FLOW_VALIDATOR" >/dev/null 2>&1 || true
  local_pg_drop "$SOLANA_FLOW_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git mvn; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
local_pg_require
local_pg_create "$SOLANA_FLOW_DB"
SOLANA_FLOW_DB_URL=$(local_pg_jdbc_url "$SOLANA_FLOW_DB")
if curl -fsS --max-time 2 -H 'content-type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"getHealth"}' \
    "http://127.0.0.1:${SOLANA_FLOW_RPC_PORT}" >/dev/null 2>&1; then
  printf 'Solana RPC port %s is already in use; stop it before running this isolated test\n' \
    "$SOLANA_FLOW_RPC_PORT" >&2
  exit 1
fi

docker run -d --name "$SOLANA_FLOW_VALIDATOR" \
  --ulimit nofile=1000000:1000000 \
  -p "127.0.0.1:${SOLANA_FLOW_RPC_PORT}:8899" \
  solanalabs/solana:v1.18.26 >/dev/null

for attempt in $(seq 1 120); do
  if curl -fsS --max-time 2 -H 'content-type: application/json' \
      -d '{"jsonrpc":"2.0","id":1,"method":"getHealth"}' \
      "http://127.0.0.1:${SOLANA_FLOW_RPC_PORT}" 2>/dev/null | grep -q '"result":"ok"'; then
    break
  fi
  if ! docker inspect -f '{{.State.Running}}' "$SOLANA_FLOW_VALIDATOR" 2>/dev/null | grep -q true; then
    printf 'Solana validator stopped before becoming ready\n' >&2
    docker logs --tail 100 "$SOLANA_FLOW_VALIDATOR" >&2 || true
    exit 1
  fi
  if [[ "$attempt" == 120 ]]; then
    printf 'Solana validator did not become ready in 120 seconds\n' >&2
    docker logs --tail 100 "$SOLANA_FLOW_VALIDATOR" >&2 || true
    exit 1
  fi
  sleep 1
done

local_pg_psql "$SOLANA_FLOW_DB" -v ON_ERROR_STOP=1 -q \
  -f "$SOLANA_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql"

SW_ED25519_SEED="$SOLANA_TEST_MASTER_SEED" \
SOLANA_DB_URL="$SOLANA_FLOW_DB_URL" \
SOLANA_DB_USER="$REGTEST_PG_USER" \
SOLANA_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
SOLANA_RPC_URL="http://127.0.0.1:${SOLANA_FLOW_RPC_PORT}" \
mvn -f "$SOLANA_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=SolanaDevnetLiveFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsolana.live.enabled=true \
  test

local_pg_psql "$SOLANA_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='SOLANA' and status='CREDITED') <> 7 then
    raise exception 'expected three SOL and four SPL credited deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='SOLANA' and status='CONFIRMED') <> 3 then
    raise exception 'expected confirmed SOL, USDT, and USDC withdrawals';
  end if;
  if (select count(*) from withdrawal_order where chain='SOLANA' and status='FAILED') <> 1 then
    raise exception 'expected the insufficient SOL withdrawal to fail';
  end if;
  if (select count(*) from collection_record where chain='SOLANA' and status='CONFIRMED') <> 3 then
    raise exception 'expected confirmed SOL, USDT, and USDC collections';
  end if;
  if (select coalesce(sum(total_balance),0) from ledger_balance where chain='SOLANA' and asset_symbol='USDT') <> 13 then
    raise exception 'USDT ledger total must be exactly 13';
  end if;
  if (select coalesce(sum(total_balance),0) from ledger_balance where chain='SOLANA' and asset_symbol='USDC') <> 13 then
    raise exception 'USDC ledger total must be exactly 13';
  end if;
  if exists (select 1 from ledger_balance where chain='SOLANA' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative Solana ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='SOLANA' and locked_balance <> 0) then
    raise exception 'non-zero locked Solana balance remains';
  end if;
end
\$\$;"

printf 'SOLANA PASS local SOL/USDT/USDC deposit, replay, withdrawal, collection, fee payer, and reconciliation\n'
