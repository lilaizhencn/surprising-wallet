#!/usr/bin/env bash
set -euo pipefail

APTOS_SAAS_SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source "$APTOS_SAAS_SOURCE_ROOT/scripts/regtest/local-postgres.sh"

APTOS_SAAS_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-aptos-saas-build.XXXXXX)
APTOS_SAAS_TMP=$(mktemp -d -t surprising-aptos-saas.XXXXXX)
APTOS_SAAS_DB="surprising_wallet_test_aptos_saas_$$"
APTOS_SAAS_NODE_PID=""
APTOS_SAAS_WALLET_PID=""
APTOS_SAAS_DEMO_PID=""
APTOS_SAAS_PLATFORM_EMAIL=platform-aptos-e2e@example.test
APTOS_SAAS_PLATFORM_PASSWORD=aptos-e2e-platform-password
APTOS_SAAS_TENANT_PASSWORD=aptos-e2e-tenant-password

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  for pid in "$APTOS_SAAS_DEMO_PID" "$APTOS_SAAS_WALLET_PID" "$APTOS_SAAS_NODE_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ "$exit_code" != 0 ]]; then
    for log in wallet.log demo.log localnet.log; do
      if [[ -f "$APTOS_SAAS_TMP/$log" ]]; then
        printf '\n--- %s (tail) ---\n' "$log" >&2
        tail -80 "$APTOS_SAAS_TMP/$log" >&2 || true
      fi
    done
  fi
  local_pg_drop "$APTOS_SAAS_DB" >/dev/null 2>&1 || true
  if [[ "$APTOS_SAAS_TMP" == *"/surprising-aptos-saas."* ]] && [[ -d "$APTOS_SAAS_TMP" ]]; then
    trash "$APTOS_SAAS_TMP"
  fi
  if [[ "$APTOS_SAAS_BUILD_ROOT" == /tmp/surprising-wallet-aptos-saas-build.* ]] \
      && [[ -d "$APTOS_SAAS_BUILD_ROOT" ]]; then
    trash "$APTOS_SAAS_BUILD_ROOT"
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command in aptos curl jq java mvn node npm openssl rsync trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require

for url in \
  http://127.0.0.1:8080/v1 \
  http://127.0.0.1:18002/actuator/health \
  http://127.0.0.1:19300/health; do
  if curl -fsS --max-time 1 "$url" >/dev/null 2>&1; then
    printf 'test endpoint is already in use: %s\n' "$url" >&2
    exit 1
  fi
done

rsync -a \
  --exclude .git \
  --exclude .codegraph \
  --exclude target \
  --exclude node_modules \
  --exclude artifacts \
  --exclude logs \
  "$APTOS_SAAS_SOURCE_ROOT/" "$APTOS_SAAS_BUILD_ROOT/"

mvn -q -f "$APTOS_SAAS_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-server -am \
  -Dmaven.test.skip=true package
APTOS_SAAS_WALLET_JAR=$(find \
  "$APTOS_SAAS_BUILD_ROOT/backendservices/wallet-parent/wallet-server/target" \
  -maxdepth 1 -type f -name 'wallet-server-*.jar' ! -name '*.original' | head -1)
if [[ -z "$APTOS_SAAS_WALLET_JAR" ]]; then
  printf 'wallet-server jar was not produced\n' >&2
  exit 1
fi

aptos node run-localnet \
  --test-dir "$APTOS_SAAS_TMP/localnet" \
  --force-restart \
  --seed 9420219420219420219420219420219420219420219420219420219420219420 \
  --no-txn-stream \
  --faucet-port 18081 \
  --ready-server-listen-port 18070 \
  --assume-yes >"$APTOS_SAAS_TMP/localnet.log" 2>&1 &
APTOS_SAAS_NODE_PID=$!

for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:18070/ >/dev/null 2>&1; then break; fi
  if ! kill -0 "$APTOS_SAAS_NODE_PID" 2>/dev/null; then exit 1; fi
  if [[ "$attempt" == 60 ]]; then exit 1; fi
  sleep 1
done

mkdir -p "$APTOS_SAAS_TMP/admin"
APTOS_SAAS_ADMIN_SEED=$(openssl rand -hex 32)
(
  cd "$APTOS_SAAS_TMP/admin"
  aptos init \
    --network custom \
    --rest-url http://127.0.0.1:8080 \
    --faucet-url http://127.0.0.1:18081 \
    --random-seed "$APTOS_SAAS_ADMIN_SEED" \
    --profile fa-admin \
    --assume-yes </dev/null >/dev/null
)
unset APTOS_SAAS_ADMIN_SEED

APTOS_SAAS_PUBLISHER=$(awk '/account:/{print $2; exit}' "$APTOS_SAAS_TMP/admin/.aptos/config.yaml")
if [[ "$APTOS_SAAS_PUBLISHER" =~ ^[0-9a-f]{64}$ ]]; then
  APTOS_SAAS_PUBLISHER="0x$APTOS_SAAS_PUBLISHER"
fi
if [[ ! "$APTOS_SAAS_PUBLISHER" =~ ^0x[0-9a-f]{64}$ ]]; then
  printf 'failed to resolve Aptos FA publisher\n' >&2
  exit 1
fi

(
  cd "$APTOS_SAAS_TMP/admin"
  aptos account fund-with-faucet \
    --account "$APTOS_SAAS_PUBLISHER" \
    --amount 2000000000 \
    --profile fa-admin >/dev/null
)

(
  cd "$APTOS_SAAS_TMP/admin"
  aptos move publish \
    --package-dir "$APTOS_SAAS_SOURCE_ROOT/infra/aptos/fa-test" \
    --output-dir "$APTOS_SAAS_TMP/fa-build" \
    --named-addresses "test_fa=$APTOS_SAAS_PUBLISHER" \
    --profile fa-admin \
    --skip-fetch-latest-git-deps \
    --assume-yes >/dev/null
)
APTOS_SAAS_METADATA=$(
  cd "$APTOS_SAAS_TMP/admin"
  aptos move view \
    --function-id "$APTOS_SAAS_PUBLISHER::test_assets::metadata_addresses" \
    --profile fa-admin
)
APTOS_SAAS_USDC=$(jq -er '.Result[0]' <<<"$APTOS_SAAS_METADATA")
APTOS_SAAS_USDT=$(jq -er '.Result[1]' <<<"$APTOS_SAAS_METADATA")

local_pg_create "$APTOS_SAAS_DB"
APTOS_SAAS_DB_URL=$(local_pg_jdbc_url "$APTOS_SAAS_DB")
local_pg_psql "$APTOS_SAAS_DB" -q -v ON_ERROR_STOP=1 \
  -f "$APTOS_SAAS_SOURCE_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$APTOS_SAAS_DB" -q -v ON_ERROR_STOP=1 \
  -v usdc="$APTOS_SAAS_USDC" -v usdt="$APTOS_SAAS_USDT" <<'SQL'
create extension if not exists pgcrypto;
insert into wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
values
    (1,
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     'aptos-saas-local-test');
update chain_profile
   set enabled=false, scan_enabled=false, withdraw_enabled=false,
       collection_enabled=false, transfer_enabled=false, updated_at=now();
update token_config
   set enabled=false, collect_enabled=false, updated_at=now();
update chain_asset
   set active=false, updated_at=now()
 where native_asset=false;
update chain_profile
   set rpc_url='http://127.0.0.1:8080/v1', chain_id=4,
       deposit_confirmations=1, withdraw_confirmations=1,
       enabled=true, scan_enabled=true, withdraw_enabled=true,
       collection_enabled=true, transfer_enabled=true, updated_at=now()
 where chain='APTOS' and network='testnet';
update chain_rpc_node
   set enabled=false, updated_at=now()
 where chain='APTOS';
update chain_rpc_node
   set rpc_url='http://127.0.0.1:8080/v1', connection_type='HTTP_REST',
       auth_type='NONE', auth_header_name=null, api_key=null, username=null, password=null,
       min_request_interval_ms=0, enabled=true, updated_at=now()
 where chain='APTOS' and network='testnet' and environment='dev' and purpose='rpc';
update token_config
   set contract_address=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       contract_address_hex=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       enabled=true, collect_enabled=true, updated_at=now()
 where chain='APTOS' and network='testnet' and symbol in ('USDC','USDT');
update chain_asset
   set contract_address=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       active=true, updated_at=now()
 where chain='APTOS' and symbol in ('USDC','USDT');
SQL

SW_DB_URL="$APTOS_SAAS_DB_URL" \
SW_DB_USERNAME="$REGTEST_PG_USER" \
SW_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
SW_CUSTODY_PLATFORM_ADMIN_EMAIL="$APTOS_SAAS_PLATFORM_EMAIL" \
SW_CUSTODY_PLATFORM_ADMIN_PASSWORD="$APTOS_SAAS_PLATFORM_PASSWORD" \
SERVER_PORT=18002 \
java -jar "$APTOS_SAAS_WALLET_JAR" >"$APTOS_SAAS_TMP/wallet.log" 2>&1 &
APTOS_SAAS_WALLET_PID=$!

for attempt in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:18002/actuator/health 2>/dev/null \
      | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APTOS_SAAS_WALLET_PID" 2>/dev/null \
      || grep -q 'Application run failed' "$APTOS_SAAS_TMP/wallet.log"; then
    exit 1
  fi
  if [[ "$attempt" == 90 ]]; then exit 1; fi
  sleep 1
done

TENANT_DEMO_PG_HOST="$REGTEST_PG_HOST" \
TENANT_DEMO_PG_PORT="$REGTEST_PG_PORT" \
TENANT_DEMO_PG_DATABASE="$APTOS_SAAS_DB" \
TENANT_DEMO_PG_USER="$REGTEST_PG_USER" \
TENANT_DEMO_PG_PASSWORD="$REGTEST_PG_PASSWORD" \
TENANT_DEMO_PG_SCHEMA=tenant_demo \
TENANT_DEMO_PORT=19300 \
node "$APTOS_SAAS_SOURCE_ROOT/tenant-demo/src/server.js" >"$APTOS_SAAS_TMP/demo.log" 2>&1 &
APTOS_SAAS_DEMO_PID=$!

for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:19300/health | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APTOS_SAAS_DEMO_PID" 2>/dev/null; then exit 1; fi
  if [[ "$attempt" == 30 ]]; then exit 1; fi
  sleep 1
done

WALLET_BASE_URL=http://127.0.0.1:18002 \
DEMO_BASE_URL=http://127.0.0.1:19300 \
PLATFORM_ADMIN_EMAIL="$APTOS_SAAS_PLATFORM_EMAIL" \
PLATFORM_ADMIN_PASSWORD="$APTOS_SAAS_PLATFORM_PASSWORD" \
TENANT_ADMIN_PASSWORD="$APTOS_SAAS_TENANT_PASSWORD" \
TEST_RUN_ID=aptos-saas-e2e \
TEST_CHAIN=APTOS \
node "$APTOS_SAAS_SOURCE_ROOT/tenant-demo/scripts/bootstrap-tenant.js" \
  >"$APTOS_SAAS_TMP/bootstrap.json"

DEMO_BASE_URL=http://127.0.0.1:19300 \
APTOS_ADMIN_DIRECTORY="$APTOS_SAAS_TMP/admin" \
APTOS_FA_PUBLISHER="$APTOS_SAAS_PUBLISHER" \
TENANT_DEMO_PG_HOST="$REGTEST_PG_HOST" \
TENANT_DEMO_PG_PORT="$REGTEST_PG_PORT" \
TENANT_DEMO_PG_DATABASE="$APTOS_SAAS_DB" \
TENANT_DEMO_PG_USER="$REGTEST_PG_USER" \
TENANT_DEMO_PG_PASSWORD="$REGTEST_PG_PASSWORD" \
node "$APTOS_SAAS_SOURCE_ROOT/tenant-demo/scripts/verify-aptos-e2e.js" \
  >"$APTOS_SAAS_TMP/result.json"

local_pg_psql "$APTOS_SAAS_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='APTOS' and status='CREDITED') <> 3 then
    raise exception 'expected three credited Aptos deposits';
  end if;
  if (select count(*) from collection_record where chain='APTOS' and status='CONFIRMED') <> 3 then
    raise exception 'expected three confirmed Aptos collections';
  end if;
  if (select count(*) from withdrawal_order where chain='APTOS' and status='CONFIRMED') <> 3 then
    raise exception 'expected three confirmed Aptos withdrawals';
  end if;
  if exists (
      select 1 from collection_record c
      join custody_gas_account g on g.tenant_id=c.tenant_id and g.chain=c.chain
      join custody_address a on a.id=g.custody_address_id and a.tenant_id=g.tenant_id
      where c.chain='APTOS' and lower(c.to_address) <> lower(a.address)
  ) then
    raise exception 'Aptos collection did not use the tenant collection address';
  end if;
  if exists (select 1 from deposit_record where chain='APTOS' and tenant_id is null)
     or exists (select 1 from collection_record where chain='APTOS' and tenant_id is null)
     or exists (select 1 from withdrawal_order where chain='APTOS' and tenant_id is null) then
    raise exception 'tenantless Aptos financial record detected';
  end if;
  if exists (select 1 from ledger_balance where available_balance < 0 or locked_balance < 0 or total_balance < 0) then
    raise exception 'negative wallet ledger balance detected';
  end if;
  if (select count(*) from custody_webhook_delivery where status='DELIVERED') < 6 then
    raise exception 'expected delivered Aptos webhook callbacks';
  end if;
  if (select count(*) from tenant_demo.webhook_events where processed=true) < 6 then
    raise exception 'tenant demo did not process Aptos callbacks';
  end if;
end
\$\$;"

jq -e '.ok == true and (.withdrawals | length) == 3' "$APTOS_SAAS_TMP/result.json" >/dev/null
printf 'Aptos SaaS API/Webhook flow passed\n'
jq '{subject, depositAddress, rotatedAddress, collectionAddress, withdrawals, balances}' \
  "$APTOS_SAAS_TMP/result.json"
