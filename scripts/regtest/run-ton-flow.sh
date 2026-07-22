#!/usr/bin/env bash
set -euo pipefail

TON_FLOW_ROOT=$(git rev-parse --show-toplevel)
source "$TON_FLOW_ROOT/scripts/regtest/local-postgres.sh"
TON_FLOW_DB="surprising_wallet_test_ton_$$"
TON_FLOW_RPC_URL=${TON_FLOW_RPC_URL:-http://127.0.0.1:8081}
TON_TEST_MASTER_SEED=${TON_TEST_MASTER_SEED:-000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}
TON_TEST_SOURCE_ADDRESS=0QBi-S8V1mNK01lqxSNfYUQk3pX3ays7bbjYqgAG1SpHOjkS

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$TON_FLOW_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl git jq mvn; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

local_pg_require
local_pg_create "$TON_FLOW_DB"
TON_FLOW_DB_URL=$(local_pg_jdbc_url "$TON_FLOW_DB")
curl -fsS --max-time 10 "$TON_FLOW_RPC_URL/getMasterchainInfo" \
  | jq -e '.ok == true' >/dev/null || {
    printf 'TON v2 RPC is not ready at %s; start an isolated MyLocalTon network first\n' \
      "$TON_FLOW_RPC_URL" >&2
    exit 1
  }

source_balance=$(curl -fsS --max-time 10 \
  "$TON_FLOW_RPC_URL/getAddressBalance?address=$TON_TEST_SOURCE_ADDRESS" | jq -er '.result | tonumber')
if (( source_balance <= 16000000000 )); then
  printf 'fund deterministic MyLocalTon source %s with more than 16 TON before running the flow\n' \
    "$TON_TEST_SOURCE_ADDRESS" >&2
  exit 1
fi

local_pg_psql "$TON_FLOW_DB" -v ON_ERROR_STOP=1 -q \
  -f "$TON_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql"

SW_ED25519_SEED="$TON_TEST_MASTER_SEED" \
TON_DB_URL="$TON_FLOW_DB_URL" \
TON_DB_USER="$REGTEST_PG_USER" \
TON_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
TON_RPC_URL="$TON_FLOW_RPC_URL" \
mvn -f "$TON_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=TonCenterClientTest,TonDatabaseFlowIntegrationTest,TonLocalFundingIntegrationTest,TonLiveMockJettonFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dton.db.enabled=true \
  -Dton.local.funding.enabled=true \
  -Dton.live.enabled=true \
  test

local_pg_psql "$TON_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='TON' and status='CREDITED'
      and asset_symbol in ('TON','USDT','USDC')) < 3 then
    raise exception 'expected credited TON, USDT, and USDC deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='TON' and status='CONFIRMED'
      and asset_symbol in ('TON','USDT','USDC')) <> 3 then
    raise exception 'expected confirmed TON, USDT, and USDC withdrawals';
  end if;
  if (select count(*) from collection_record where chain='TON' and status='CONFIRMED'
      and asset_symbol in ('TON','USDT','USDC')) <> 3 then
    raise exception 'expected confirmed TON, USDT, and USDC collections';
  end if;
  if exists (select 1 from ledger_balance where chain='TON'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative TON ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='TON' and locked_balance <> 0) then
    raise exception 'non-zero locked TON balance remains';
  end if;
  if exists (select 1 from withdrawal_order where chain='TON' and status <> 'CONFIRMED') then
    raise exception 'unresolved TON withdrawal remains';
  end if;
  if exists (select 1 from collection_record where chain='TON' and status <> 'CONFIRMED') then
    raise exception 'unresolved TON collection remains';
  end if;
end
\$\$;"

printf 'TON PASS local TON/USDT/USDC deposit, replay, withdrawal, collection, BOC retry, and ledger audit\n'
