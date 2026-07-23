#!/usr/bin/env bash
set -euo pipefail

NEAR_FLOW_SOURCE_ROOT=$(git rev-parse --show-toplevel)
source "$NEAR_FLOW_SOURCE_ROOT/scripts/regtest/local-postgres.sh"
NEAR_FLOW_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-near-build.XXXXXX)
NEAR_FLOW_ROOT="$NEAR_FLOW_BUILD_ROOT"
NEAR_FLOW_DB="surprising_wallet_test_near_$$"
NEAR_FLOW_NODE="surprising-wallet-near-sandbox-$$"
NEAR_FLOW_RPC_PORT=${NEAR_FLOW_RPC_PORT:-3032}
NEAR_FLOW_ED25519_SEED=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  docker rm -f "$NEAR_FLOW_NODE" >/dev/null 2>&1 || true
  local_pg_drop "$NEAR_FLOW_DB" >/dev/null 2>&1 || true
  if [[ "$NEAR_FLOW_BUILD_ROOT" == /tmp/surprising-wallet-near-build.* ]]; then
    rm -rf "$NEAR_FLOW_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git mvn rsync; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

rsync -a \
  --exclude=.git \
  --exclude=.codegraph \
  --exclude=target \
  --exclude=node_modules \
  --exclude=/resources/infra/evm-fork/artifacts \
  --exclude=logs \
  "$NEAR_FLOW_SOURCE_ROOT/" "$NEAR_FLOW_BUILD_ROOT/"

docker info >/dev/null
local_pg_require
local_pg_create "$NEAR_FLOW_DB"
NEAR_FLOW_DB_URL=$(local_pg_jdbc_url "$NEAR_FLOW_DB")

local_pg_psql "$NEAR_FLOW_DB" -v ON_ERROR_STOP=1 -q \
  -f "$NEAR_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$NEAR_FLOW_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(decode(repeat('11', 32), 'hex'), 'base64'),
     encode(decode(repeat('22', 32), 'hex'), 'base64'),
     encode(decode(repeat('33', 32), 'hex'), 'base64'),
     encode(decode('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f', 'hex'), 'base64'),
     'near-sandbox-test');
UPDATE chain_profile SET enabled = false WHERE chain = 'NEAR';
UPDATE chain_profile
   SET enabled = true, scan_enabled = true, withdraw_enabled = true,
       collection_enabled = true, transfer_enabled = true,
       deposit_confirmations = 1, withdraw_confirmations = 1,
       scan_batch_size = 100, scan_start_height = 0, scan_max_blocks_per_run = 100
 WHERE chain = 'NEAR' AND network = 'testnet';
UPDATE token_config SET enabled = false WHERE chain = 'NEAR';
SQL

docker run -d --name "$NEAR_FLOW_NODE" \
  -p "127.0.0.1:${NEAR_FLOW_RPC_PORT}:3030" \
  -v "$NEAR_FLOW_SOURCE_ROOT:/workspace:ro" \
  node:22-trixie bash -lc \
  "npm install -g near-sandbox >/tmp/npm.log 2>&1 && \
   near-sandbox --home /tmp/near init >/tmp/init.log 2>&1 && \
   node /workspace/scripts/regtest/near-sandbox-genesis.mjs /tmp/near '$NEAR_FLOW_ED25519_SEED' && \
   exec near-sandbox --home /tmp/near run --rpc-addr 0.0.0.0:3030 --network-addr 0.0.0.0:24567" \
  >/dev/null

for attempt in $(seq 1 90); do
  if curl -fsS --max-time 2 \
      -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":"ready","method":"status","params":[]}' \
      "http://127.0.0.1:${NEAR_FLOW_RPC_PORT}" >/dev/null 2>&1; then
    break
  fi
  if ! docker inspect -f '{{.State.Running}}' "$NEAR_FLOW_NODE" 2>/dev/null | grep -q true; then
    docker logs "$NEAR_FLOW_NODE" >&2
    exit 1
  fi
  if [[ "$attempt" == 90 ]]; then
    printf 'NEAR sandbox did not become ready\n' >&2
    docker logs "$NEAR_FLOW_NODE" >&2
    exit 1
  fi
  sleep 1
done

mvn -f "$NEAR_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=NearAddressGenerationTest,NearDepositScannerTest,NearRpcClientTest,NearTransactionSignerTest,NearSandboxFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dnear.sandbox.flow.enabled=true \
  -Dnear.sandbox.rpc="http://127.0.0.1:${NEAR_FLOW_RPC_PORT}" \
  -Dnear.nep141.wasm="$NEAR_FLOW_ROOT/backendservices/wallet-parent/wallet-server/src/main/resources/contracts/near/artifacts/TokDouNep141.wasm" \
  -Dnear.db.url="$NEAR_FLOW_DB_URL" \
  -Dnear.db.user="$REGTEST_PG_USER" \
  -Dnear.db.password="$REGTEST_PG_PASSWORD" \
  test

local_pg_psql "$NEAR_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='NEAR' and status='CREDITED'
      and asset_symbol in ('NEAR','USDC','USDT')) < 3 then
    raise exception 'expected credited NEAR, USDC, and USDT deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='NEAR' and status='CONFIRMED'
      and asset_symbol in ('NEAR','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed NEAR, USDC, and USDT withdrawals';
  end if;
  if (select count(*) from collection_record where chain='NEAR' and status='CONFIRMED'
      and asset_symbol in ('NEAR','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed NEAR, USDC, and USDT collections';
  end if;
  if exists (select 1 from ledger_balance where chain='NEAR'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative NEAR ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='NEAR' and locked_balance <> 0) then
    raise exception 'non-zero locked NEAR balance remains';
  end if;
  if exists (select 1 from withdrawal_order where chain='NEAR' and status <> 'CONFIRMED') then
    raise exception 'unresolved NEAR withdrawal remains';
  end if;
  if exists (select 1 from collection_record where chain='NEAR' and status <> 'CONFIRMED') then
    raise exception 'unresolved NEAR collection remains';
  end if;
end
\$\$;"

printf 'NEAR PASS local sandbox NEAR/USDC/USDT deposit, replay, withdrawal, collection, and ledger audit\n'
