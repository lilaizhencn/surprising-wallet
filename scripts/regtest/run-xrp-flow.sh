#!/usr/bin/env bash
set -euo pipefail

XRP_FLOW_ROOT=$(git rev-parse --show-toplevel)
source "$XRP_FLOW_ROOT/scripts/regtest/local-postgres.sh"
XRP_FLOW_DB="surprising_wallet_test_xrp_$$"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$XRP_FLOW_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in git mvn; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

local_pg_require
local_pg_create "$XRP_FLOW_DB"
XRP_FLOW_DB_URL=$(local_pg_jdbc_url "$XRP_FLOW_DB")

local_pg_psql "$XRP_FLOW_DB" -v ON_ERROR_STOP=1 -q \
  -f "$XRP_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$XRP_FLOW_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     'xrp-live-test');
SQL

mvn -f "$XRP_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=XrpAddressGenerationTest,XrpIssuedCurrencyTest,XrpTestnetFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dxrp.live.flow.enabled=true \
  -Dxrp.db.url="$XRP_FLOW_DB_URL" \
  -Dxrp.db.user="$REGTEST_PG_USER" \
  -Dxrp.db.password="$REGTEST_PG_PASSWORD" \
  test

local_pg_psql "$XRP_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='XRP' and status='CREDITED'
      and asset_symbol in ('XRP','USDC','USDT')) <> 3 then
    raise exception 'expected credited XRP, USDC, and USDT deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='XRP' and status='CONFIRMED'
      and asset_symbol in ('XRP','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed XRP, USDC, and USDT withdrawals';
  end if;
  if (select count(*) from collection_record where chain='XRP' and status='CONFIRMED'
      and asset_symbol in ('XRP','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed XRP, USDC, and USDT collections';
  end if;
  if exists (select 1 from ledger_balance where chain='XRP'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative XRP ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='XRP' and locked_balance <> 0) then
    raise exception 'non-zero locked XRP balance remains';
  end if;
  if exists (select 1 from withdrawal_order where chain='XRP' and status <> 'CONFIRMED') then
    raise exception 'unresolved XRP withdrawal remains';
  end if;
  if exists (select 1 from collection_record where chain='XRP' and status <> 'CONFIRMED') then
    raise exception 'unresolved XRP collection remains';
  end if;
end
\$\$;"

printf 'XRP PASS Testnet XRP/USDC/USDT deposit, replay, withdrawal, collection, trustline, and ledger audit\n'
