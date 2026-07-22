#!/usr/bin/env bash
set -euo pipefail

TON_FLOW_ROOT=$(git rev-parse --show-toplevel)
TON_FLOW_DB="surprising-wallet-ton-flow-$$"
TON_FLOW_DB_PORT=${TON_FLOW_DB_PORT:-55440}
TON_FLOW_RPC_URL=${TON_FLOW_RPC_URL:-http://127.0.0.1:8081}
TON_TEST_MASTER_SEED=${TON_TEST_MASTER_SEED:-000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}
TON_TEST_SOURCE_ADDRESS=0QBi-S8V1mNK01lqxSNfYUQk3pX3ays7bbjYqgAG1SpHOjkS

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  docker rm -f "$TON_FLOW_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git jq mvn sed; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
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

docker run -d --name "$TON_FLOW_DB" \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -e POSTGRES_DB=wallet \
  -p "127.0.0.1:${TON_FLOW_DB_PORT}:5432" \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$TON_FLOW_DB" pg_isready -U wallet -d wallet >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'TON integration PostgreSQL did not become ready\n' >&2
    exit 1
  fi
  sleep 1
done

sed '/^SET transaction_timeout = /d' "$TON_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
  | docker exec -i "$TON_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q

SW_ED25519_SEED="$TON_TEST_MASTER_SEED" \
TON_DB_URL="jdbc:postgresql://127.0.0.1:${TON_FLOW_DB_PORT}/wallet" \
TON_DB_USER=wallet \
TON_DB_PASSWORD=wallet \
TON_RPC_URL="$TON_FLOW_RPC_URL" \
mvn -f "$TON_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=TonCenterClientTest,TonDatabaseFlowIntegrationTest,TonLocalFundingIntegrationTest,TonLiveMockJettonFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dton.db.enabled=true \
  -Dton.local.funding.enabled=true \
  -Dton.live.enabled=true \
  test

docker exec "$TON_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -Atqc "
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
