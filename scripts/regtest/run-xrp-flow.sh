#!/usr/bin/env bash
set -euo pipefail

XRP_FLOW_ROOT=$(git rev-parse --show-toplevel)
XRP_FLOW_DB="surprising-wallet-xrp-flow-$$"
XRP_FLOW_DB_PORT=${XRP_FLOW_DB_PORT:-55441}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  docker rm -f "$XRP_FLOW_DB" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in docker git mvn sed; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
docker run -d --name "$XRP_FLOW_DB" \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -e POSTGRES_DB=wallet \
  -p "127.0.0.1:${XRP_FLOW_DB_PORT}:5432" \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$XRP_FLOW_DB" pg_isready -U wallet -d wallet >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'XRP integration PostgreSQL did not become ready\n' >&2
    exit 1
  fi
  sleep 1
done

sed '/^SET transaction_timeout = /d' "$XRP_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
  | docker exec -i "$XRP_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q
docker exec -i "$XRP_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q <<'SQL'
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
  -Dxrp.db.url="jdbc:postgresql://127.0.0.1:${XRP_FLOW_DB_PORT}/wallet" \
  -Dxrp.db.user=wallet \
  -Dxrp.db.password=wallet \
  test

docker exec "$XRP_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='XRP' and status='CREDITED'
      and asset_symbol in ('XRP','USDC')) <> 2 then
    raise exception 'expected credited XRP and USDC deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='XRP' and status='CONFIRMED'
      and asset_symbol in ('XRP','USDC')) <> 2 then
    raise exception 'expected confirmed XRP and USDC withdrawals';
  end if;
  if (select count(*) from collection_record where chain='XRP' and status='CONFIRMED'
      and asset_symbol in ('XRP','USDC')) <> 2 then
    raise exception 'expected confirmed XRP and USDC collections';
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

printf 'XRP PASS Testnet XRP/USDC deposit, replay, withdrawal, collection, trustline, and ledger audit\n'
