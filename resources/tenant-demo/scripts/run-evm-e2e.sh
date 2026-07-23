#!/usr/bin/env bash
set -euo pipefail

EVM_SAAS_SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source "$EVM_SAAS_SOURCE_ROOT/scripts/regtest/local-postgres.sh"

EVM_SAAS_CHAIN=${TEST_CHAIN:-ETH}
EVM_SAAS_NETWORK=${TEST_NETWORK:-sepolia}
EVM_SAAS_NATIVE_SYMBOL=${NATIVE_SYMBOL:-ETH}
EVM_SAAS_CHAIN_ID=${EVM_CHAIN_ID:-11155111}
EVM_SAAS_TOKEN_SYMBOLS=${TOKEN_SYMBOLS:-USDC,USDT}
EVM_SAAS_SLUG=$(tr '[:upper:]' '[:lower:]' <<<"$EVM_SAAS_CHAIN" | tr -cd 'a-z0-9_')
if [[ ! "$EVM_SAAS_CHAIN" =~ ^[A-Z0-9_]+$ ]] \
    || [[ ! "$EVM_SAAS_NETWORK" =~ ^[a-z0-9_-]+$ ]] \
    || [[ ! "$EVM_SAAS_CHAIN_ID" =~ ^[0-9]+$ ]] \
    || [[ ! "$EVM_SAAS_TOKEN_SYMBOLS" =~ ^([A-Z0-9_]+(,[A-Z0-9_]+)*)?$ ]]; then
  printf 'invalid EVM test chain configuration\n' >&2
  exit 1
fi
if [[ -n "$EVM_SAAS_TOKEN_SYMBOLS" ]]; then
  EVM_SAAS_ASSET_COUNT=$((2 + $(tr -cd ',' <<<"$EVM_SAAS_TOKEN_SYMBOLS" | wc -c | tr -d ' ')))
else
  EVM_SAAS_ASSET_COUNT=1
fi
EVM_SAAS_BUILD_ROOT=$(mktemp -d "/tmp/surprising-wallet-${EVM_SAAS_SLUG}-saas-build.XXXXXX")
EVM_SAAS_TMP=$(mktemp -d -t "surprising-${EVM_SAAS_SLUG}-saas.XXXXXX")
EVM_SAAS_DB="surprising_wallet_test_${EVM_SAAS_SLUG}_saas_$$"
EVM_SAAS_NODE_PID=""
EVM_SAAS_WALLET_PID=""
EVM_SAAS_DEMO_PID=""
EVM_SAAS_PLATFORM_EMAIL="platform-${EVM_SAAS_SLUG}-e2e@example.test"
EVM_SAAS_PLATFORM_PASSWORD="${EVM_SAAS_SLUG}-e2e-platform-password"
EVM_SAAS_TENANT_PASSWORD="${EVM_SAAS_SLUG}-e2e-tenant-password"

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  for pid in "$EVM_SAAS_DEMO_PID" "$EVM_SAAS_WALLET_PID" "$EVM_SAAS_NODE_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ "$exit_code" != 0 ]]; then
    for log in wallet.log demo.log hardhat.log; do
      if [[ -f "$EVM_SAAS_TMP/$log" ]]; then
        printf '\n--- %s (tail) ---\n' "$log" >&2
        tail -100 "$EVM_SAAS_TMP/$log" >&2 || true
      fi
    done
  fi
  local_pg_drop "$EVM_SAAS_DB" >/dev/null 2>&1 || true
  if [[ "$EVM_SAAS_TMP" == *"/surprising-${EVM_SAAS_SLUG}-saas."* ]] \
      && [[ -d "$EVM_SAAS_TMP" ]]; then
    trash "$EVM_SAAS_TMP"
  fi
  if [[ "$EVM_SAAS_BUILD_ROOT" == "/tmp/surprising-wallet-${EVM_SAAS_SLUG}-saas-build."* ]] \
      && [[ -d "$EVM_SAAS_BUILD_ROOT" ]]; then
    trash "$EVM_SAAS_BUILD_ROOT"
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command in curl jq java mvn node npm openssl rsync trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
local_pg_require

for url in \
  http://127.0.0.1:8545 \
  http://127.0.0.1:18002/actuator/health \
  http://127.0.0.1:19300/health; do
  if curl -fsS --max-time 1 "$url" >/dev/null 2>&1; then
    printf 'test endpoint is already in use: %s\n' "$url" >&2
    exit 1
  fi
done

if [[ ! -d "$EVM_SAAS_SOURCE_ROOT/resources/infra/evm-fork/node_modules" ]]; then
  npm --prefix "$EVM_SAAS_SOURCE_ROOT/resources/infra/evm-fork" ci
fi

rsync -a \
  --exclude .git \
  --exclude .codegraph \
  --exclude target \
  --exclude node_modules \
  --exclude artifacts \
  --exclude logs \
  "$EVM_SAAS_SOURCE_ROOT/" "$EVM_SAAS_BUILD_ROOT/"

mvn -q -f "$EVM_SAAS_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-server -am \
  -Dmaven.test.skip=true package
EVM_SAAS_WALLET_JAR=$(find \
  "$EVM_SAAS_BUILD_ROOT/backendservices/wallet-parent/wallet-server/target" \
  -maxdepth 1 -type f -name 'wallet-server-*.jar' ! -name '*.original' | head -1)
if [[ -z "$EVM_SAAS_WALLET_JAR" ]]; then
  printf 'wallet-server jar was not produced\n' >&2
  exit 1
fi

(
  cd "$EVM_SAAS_SOURCE_ROOT/resources/infra/evm-fork"
  exec env HARDHAT_CHAIN_ID="$EVM_SAAS_CHAIN_ID" HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
    ./node_modules/.bin/hardhat node --hostname 127.0.0.1 --port 8545
) >"$EVM_SAAS_TMP/hardhat.log" 2>&1 &
EVM_SAAS_NODE_PID=$!

for attempt in $(seq 1 60); do
  response=$(curl -fsS --max-time 1 \
    -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    http://127.0.0.1:8545 2>/dev/null || true)
  if [[ -n "$response" ]] \
      && [[ $(jq -r '.result' <<<"$response") == $(printf '0x%x' "$EVM_SAAS_CHAIN_ID") ]]; then
    break
  fi
  if ! kill -0 "$EVM_SAAS_NODE_PID" 2>/dev/null || [[ "$attempt" == 60 ]]; then
    exit 1
  fi
  sleep 1
done

local_pg_create "$EVM_SAAS_DB"
EVM_SAAS_DB_URL=$(local_pg_jdbc_url "$EVM_SAAS_DB")
local_pg_psql "$EVM_SAAS_DB" -q -v ON_ERROR_STOP=1 \
  -f "$EVM_SAAS_SOURCE_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$EVM_SAAS_DB" -q -v ON_ERROR_STOP=1 \
  -v chain="$EVM_SAAS_CHAIN" -v network="$EVM_SAAS_NETWORK" \
  -v chain_id="$EVM_SAAS_CHAIN_ID" -v symbols="$EVM_SAAS_TOKEN_SYMBOLS" <<'SQL'
create extension if not exists pgcrypto;
insert into wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
values
    (1,
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     'evm-saas-local-test');
update chain_profile
   set enabled=false, scan_enabled=false, withdraw_enabled=false,
       collection_enabled=false, transfer_enabled=false, updated_at=now();
update token_config
   set enabled=false, collect_enabled=false, updated_at=now();
update chain_asset
   set active=false, updated_at=now();
update chain_profile
   set rpc_url='http://127.0.0.1:8545', chain_id=:'chain_id',
       deposit_confirmations=1, withdraw_confirmations=1,
       enabled=true, scan_enabled=true, withdraw_enabled=true,
       collection_enabled=true, transfer_enabled=true, updated_at=now()
 where chain=:'chain' and network=:'network';
update chain_rpc_node
   set enabled=false, updated_at=now();
update chain_rpc_node
   set rpc_url='http://127.0.0.1:8545', connection_type='HTTP_JSON_RPC',
       auth_type='NONE', auth_header_name=null, api_key=null, username=null, password=null,
       min_request_interval_ms=0, enabled=true, updated_at=now()
 where chain=:'chain' and network=:'network' and environment='dev' and purpose='rpc';
update token_config
   set enabled=true, collect_enabled=true, updated_at=now()
 where chain=:'chain' and network=:'network'
   and symbol = any(string_to_array(:'symbols', ',')::varchar[]);
update chain_asset
   set active=true, updated_at=now()
 where chain=:'chain'
   and (native_asset=true or symbol = any(string_to_array(:'symbols', ',')::varchar[]));
SQL
EVM_SAAS_PROFILE_COUNT=$(local_pg_psql "$EVM_SAAS_DB" -Atqc \
  "select count(*) from chain_profile where chain='$EVM_SAAS_CHAIN' and enabled=true")
EVM_SAAS_CONFIGURED_TOKEN_COUNT=$(local_pg_psql "$EVM_SAAS_DB" -Atqc \
  "select count(*) from token_config where chain='$EVM_SAAS_CHAIN' and enabled=true")
if [[ "$EVM_SAAS_PROFILE_COUNT" != 1 ]] \
    || [[ "$EVM_SAAS_CONFIGURED_TOKEN_COUNT" != $((EVM_SAAS_ASSET_COUNT - 1)) ]]; then
  printf '%s local profile/token configuration mismatch: profiles=%s tokens=%s\n' \
    "$EVM_SAAS_CHAIN" "$EVM_SAAS_PROFILE_COUNT" "$EVM_SAAS_CONFIGURED_TOKEN_COUNT" >&2
  exit 1
fi

EVM_CHAIN="$EVM_SAAS_CHAIN" \
EVM_NETWORK="$EVM_SAAS_NETWORK" \
TOKEN_SYMBOLS="$EVM_SAAS_TOKEN_SYMBOLS" \
PG_URL="$(local_pg_uri "$EVM_SAAS_DB")" \
DEPLOYMENT_OUT_DIR="$EVM_SAAS_TMP/deployments" \
  npm --prefix "$EVM_SAAS_SOURCE_ROOT/resources/infra/evm-fork" run deploy:mock >/dev/null
EVM_SAAS_DEPLOYMENT="$EVM_SAAS_TMP/deployments/$EVM_SAAS_CHAIN.json"

SW_DB_URL="$EVM_SAAS_DB_URL" \
SW_DB_USERNAME="$REGTEST_PG_USER" \
SW_DB_PASSWORD="$REGTEST_PG_PASSWORD" \
SW_CUSTODY_PLATFORM_ADMIN_EMAIL="$EVM_SAAS_PLATFORM_EMAIL" \
SW_CUSTODY_PLATFORM_ADMIN_PASSWORD="$EVM_SAAS_PLATFORM_PASSWORD" \
SERVER_PORT=18002 \
java -jar "$EVM_SAAS_WALLET_JAR" >"$EVM_SAAS_TMP/wallet.log" 2>&1 &
EVM_SAAS_WALLET_PID=$!

for attempt in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:18002/actuator/health 2>/dev/null \
      | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$EVM_SAAS_WALLET_PID" 2>/dev/null \
      || grep -q 'Application run failed' "$EVM_SAAS_TMP/wallet.log"; then
    exit 1
  fi
  if [[ "$attempt" == 90 ]]; then exit 1; fi
  sleep 1
done

TENANT_DEMO_PG_HOST="$REGTEST_PG_HOST" \
TENANT_DEMO_PG_PORT="$REGTEST_PG_PORT" \
TENANT_DEMO_PG_DATABASE="$EVM_SAAS_DB" \
TENANT_DEMO_PG_USER="$REGTEST_PG_USER" \
TENANT_DEMO_PG_PASSWORD="$REGTEST_PG_PASSWORD" \
TENANT_DEMO_PG_SCHEMA=tenant_demo \
TENANT_DEMO_PORT=19300 \
node "$EVM_SAAS_SOURCE_ROOT/tenant-demo/src/server.js" >"$EVM_SAAS_TMP/demo.log" 2>&1 &
EVM_SAAS_DEMO_PID=$!

for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:19300/health | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$EVM_SAAS_DEMO_PID" 2>/dev/null || [[ "$attempt" == 30 ]]; then
    exit 1
  fi
  sleep 1
done

WALLET_BASE_URL=http://127.0.0.1:18002 \
DEMO_BASE_URL=http://127.0.0.1:19300 \
PLATFORM_ADMIN_EMAIL="$EVM_SAAS_PLATFORM_EMAIL" \
PLATFORM_ADMIN_PASSWORD="$EVM_SAAS_PLATFORM_PASSWORD" \
TENANT_ADMIN_PASSWORD="$EVM_SAAS_TENANT_PASSWORD" \
TEST_RUN_ID="${EVM_SAAS_SLUG}-saas-e2e" \
TEST_CHAIN="$EVM_SAAS_CHAIN" \
node "$EVM_SAAS_SOURCE_ROOT/tenant-demo/scripts/bootstrap-tenant.js" \
  >"$EVM_SAAS_TMP/bootstrap.json"

DEMO_BASE_URL=http://127.0.0.1:19300 \
EVM_RPC_URL=http://127.0.0.1:8545 \
EVM_DEPLOYMENT_FILE="$EVM_SAAS_DEPLOYMENT" \
TEST_CHAIN="$EVM_SAAS_CHAIN" \
NATIVE_SYMBOL="$EVM_SAAS_NATIVE_SYMBOL" \
TENANT_DEMO_PG_HOST="$REGTEST_PG_HOST" \
TENANT_DEMO_PG_PORT="$REGTEST_PG_PORT" \
TENANT_DEMO_PG_DATABASE="$EVM_SAAS_DB" \
TENANT_DEMO_PG_USER="$REGTEST_PG_USER" \
TENANT_DEMO_PG_PASSWORD="$REGTEST_PG_PASSWORD" \
node "$EVM_SAAS_SOURCE_ROOT/tenant-demo/scripts/verify-evm-e2e.js" \
  >"$EVM_SAAS_TMP/result.json"

IFS='|' read -r EVM_SAAS_DEPOSIT_COUNT EVM_SAAS_COLLECTION_COUNT EVM_SAAS_WITHDRAWAL_COUNT \
  EVM_SAAS_TENANTLESS_COUNT EVM_SAAS_NEGATIVE_BALANCE_COUNT EVM_SAAS_WEBHOOK_COUNT < <(
  local_pg_psql "$EVM_SAAS_DB" -AtF '|' -c "
    select
      (select count(*) from deposit_record where chain='$EVM_SAAS_CHAIN' and status='CREDITED'),
      (select count(*) from collection_record where chain='$EVM_SAAS_CHAIN' and status='CONFIRMED'),
      (select count(*) from withdrawal_order where chain='$EVM_SAAS_CHAIN' and status='CONFIRMED'),
      (select count(*) from deposit_record where chain='$EVM_SAAS_CHAIN' and tenant_id is null)
        + (select count(*) from collection_record where chain='$EVM_SAAS_CHAIN' and tenant_id is null)
        + (select count(*) from withdrawal_order where chain='$EVM_SAAS_CHAIN' and tenant_id is null),
      (select count(*) from ledger_balance
        where available_balance < 0 or locked_balance < 0 or total_balance < 0),
      (select count(*) from custody_webhook_delivery where status='DELIVERED')")
if [[ "$EVM_SAAS_DEPOSIT_COUNT" != "$EVM_SAAS_ASSET_COUNT" ]] \
    || [[ "$EVM_SAAS_COLLECTION_COUNT" != "$EVM_SAAS_ASSET_COUNT" ]] \
    || [[ "$EVM_SAAS_WITHDRAWAL_COUNT" != "$EVM_SAAS_ASSET_COUNT" ]] \
    || [[ "$EVM_SAAS_TENANTLESS_COUNT" != 0 ]] \
    || [[ "$EVM_SAAS_NEGATIVE_BALANCE_COUNT" != 0 ]] \
    || (( EVM_SAAS_WEBHOOK_COUNT < EVM_SAAS_ASSET_COUNT * 2 )); then
  printf '%s audit failed: deposits=%s collections=%s withdrawals=%s tenantless=%s negative=%s webhooks=%s\n' \
    "$EVM_SAAS_CHAIN" "$EVM_SAAS_DEPOSIT_COUNT" "$EVM_SAAS_COLLECTION_COUNT" \
    "$EVM_SAAS_WITHDRAWAL_COUNT" "$EVM_SAAS_TENANTLESS_COUNT" \
    "$EVM_SAAS_NEGATIVE_BALANCE_COUNT" "$EVM_SAAS_WEBHOOK_COUNT" >&2
  exit 1
fi

jq -e '.ok == true' "$EVM_SAAS_TMP/result.json" >/dev/null
printf '%s SaaS API/Webhook flow passed\n' "$EVM_SAAS_CHAIN"
jq '{chain, subject, depositAddress, rotatedAddress, collectionAddress, withdrawals, balances}' \
  "$EVM_SAAS_TMP/result.json"
