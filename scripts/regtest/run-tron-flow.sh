#!/usr/bin/env bash
set -euo pipefail

TRON_FLOW_SOURCE_ROOT=$(git rev-parse --show-toplevel)
source "$TRON_FLOW_SOURCE_ROOT/scripts/regtest/local-postgres.sh"
TRON_FLOW_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-tron-build.XXXXXX)
TRON_FLOW_RUNTIME_DIR=$(mktemp -d /tmp/surprising-wallet-tron-runtime.XXXXXX)
TRON_FLOW_NODE="surprising-wallet-tron-node-$$"
TRON_FLOW_DB="surprising_wallet_test_tron_$$"
TRON_FLOW_HTTP_PORT=${TRON_FLOW_HTTP_PORT:-19090}
TRON_FLOW_GRPC_PORT=${TRON_FLOW_GRPC_PORT:-19051}
TRON_FLOW_SOLIDITY_GRPC_PORT=${TRON_FLOW_SOLIDITY_GRPC_PORT:-19052}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$TRON_FLOW_DB" >/dev/null 2>&1 || true
  if [[ "$status" != 0 && "${TRON_FLOW_KEEP_ON_FAILURE:-false}" == true ]]; then
    printf 'TRON failure resources kept: node=%s build=%s runtime=%s\n' \
      "$TRON_FLOW_NODE" "$TRON_FLOW_BUILD_ROOT" "$TRON_FLOW_RUNTIME_DIR" >&2
    exit "$status"
  fi
  docker rm -f "$TRON_FLOW_NODE" >/dev/null 2>&1 || true
  if [[ "$TRON_FLOW_BUILD_ROOT" == /tmp/surprising-wallet-tron-build.* ]]; then
    rm -rf "$TRON_FLOW_BUILD_ROOT"
  fi
  if [[ "$TRON_FLOW_RUNTIME_DIR" == /tmp/surprising-wallet-tron-runtime.* ]]; then
    rm -rf "$TRON_FLOW_RUNTIME_DIR"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git jq mvn node npm rsync; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
local_pg_require
local_pg_create "$TRON_FLOW_DB"
TRON_FLOW_DB_URL=$(local_pg_jdbc_url "$TRON_FLOW_DB")
if [[ ! -d "$TRON_FLOW_SOURCE_ROOT/evm-fork/node_modules/solc" ]]; then
  npm ci --prefix "$TRON_FLOW_SOURCE_ROOT/evm-fork"
fi

rsync -a \
  --exclude=.git \
  --exclude=.codegraph \
  --exclude=target \
  --exclude=node_modules \
  --exclude=/evm-fork/artifacts \
  --exclude=logs \
  "$TRON_FLOW_SOURCE_ROOT/" "$TRON_FLOW_BUILD_ROOT/"

local_pg_psql "$TRON_FLOW_DB" -v ON_ERROR_STOP=1 -q \
  -f "$TRON_FLOW_BUILD_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
local_pg_psql "$TRON_FLOW_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     encode(gen_random_bytes(32), 'base64'),
     'tron-local-test');
UPDATE chain_profile SET enabled = false WHERE chain = 'TRON';
UPDATE chain_profile
   SET enabled = true, scan_enabled = true, withdraw_enabled = true,
       collection_enabled = true, transfer_enabled = true,
       deposit_confirmations = 1, withdraw_confirmations = 1,
       scan_batch_size = 100, scan_start_height = 0, scan_max_blocks_per_run = 100
 WHERE chain = 'TRON' AND network = 'nile';
UPDATE chain_asset SET decimals = 6, active = true WHERE chain = 'TRON' AND symbol = 'TRX';
UPDATE token_config SET enabled = false WHERE chain = 'TRON';
SQL

docker run -d --name "$TRON_FLOW_NODE" \
  -e quiet=true \
  -e accounts=8 \
  -p "127.0.0.1:${TRON_FLOW_HTTP_PORT}:9090" \
  -p "127.0.0.1:${TRON_FLOW_GRPC_PORT}:50051" \
  -p "127.0.0.1:${TRON_FLOW_SOLIDITY_GRPC_PORT}:50052" \
  tronbox/tre:dev >/dev/null

accounts_json=''
for attempt in $(seq 1 300); do
  accounts_json=$(curl -fsS --max-time 2 \
    "http://127.0.0.1:${TRON_FLOW_HTTP_PORT}/admin/accounts-json" 2>/dev/null || true)
  if jq -e '.privateKeys | length >= 1' >/dev/null 2>&1 <<<"$accounts_json"; then
    break
  fi
  if [[ "$attempt" == 300 ]]; then
    printf 'TRON local node did not generate funded accounts\n' >&2
    docker logs "$TRON_FLOW_NODE" >&2
    exit 1
  fi
  sleep 1
done
source_private_key=$(jq -r '.privateKeys[0]' <<<"$accounts_json")

node "$TRON_FLOW_SOURCE_ROOT/scripts/regtest/tron-compile-token.js" \
  "$TRON_FLOW_RUNTIME_DIR/mock-erc20.json"
docker cp "$TRON_FLOW_RUNTIME_DIR/mock-erc20.json" "$TRON_FLOW_NODE:/tmp/mock-erc20.json"
docker cp "$TRON_FLOW_SOURCE_ROOT/scripts/regtest/tron-deploy-tokens.js" \
  "$TRON_FLOW_NODE:/tmp/tron-deploy-tokens.js"
docker exec \
  -e NODE_PATH=/tron/app/node_modules \
  -e TRON_LOCAL_SOURCE_KEY="$source_private_key" \
  "$TRON_FLOW_NODE" node /tmp/tron-deploy-tokens.js \
  /tmp/mock-erc20.json /tmp/contracts.json
docker cp "$TRON_FLOW_NODE:/tmp/contracts.json" "$TRON_FLOW_RUNTIME_DIR/contracts.json"
usdt_contract=$(jq -r '.usdtContract' "$TRON_FLOW_RUNTIME_DIR/contracts.json")
usdc_contract=$(jq -r '.usdcContract' "$TRON_FLOW_RUNTIME_DIR/contracts.json")

TRON_LOCAL_SOURCE_KEY="$source_private_key" mvn -f "$TRON_FLOW_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=Trc20AbiEncodingTest,TronAddressCodecTest,TronAddressGenerationTest,TronGasEstimatorTest,TronLedgerIdempotencyTest,TronScannerTest,TronTridentKeyFactoryTest,TronWaitingGasStateTest,TronLiveFullFlowIntegrationTest \
  -Dtron.live.flow.enabled=true \
  -Dtron.live.network=LOCAL \
  -Dtron.live.usdt.contract="$usdt_contract" \
  -Dtron.live.usdc.contract="$usdc_contract" \
  -Dtron.fullnode="127.0.0.1:${TRON_FLOW_GRPC_PORT}" \
  -Dtron.soliditynode="127.0.0.1:${TRON_FLOW_SOLIDITY_GRPC_PORT}" \
  -Dtron.confirmations=1 \
  -Dtron.db.url="$TRON_FLOW_DB_URL" \
  -Dtron.db.user="$REGTEST_PG_USER" \
  -Dtron.db.password="$REGTEST_PG_PASSWORD" \
  test

mvn -f "$TRON_FLOW_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TronTrxDepositScanIntegrationTest,TronTrxWithdrawIntegrationTest,Trc20DepositScanIntegrationTest,Trc20WithdrawIntegrationTest,Trc20CollectionIntegrationTest,TronGasTopupIntegrationTest \
  -Dtron.db.url="$TRON_FLOW_DB_URL" \
  -Dtron.db.user="$REGTEST_PG_USER" \
  -Dtron.db.password="$REGTEST_PG_PASSWORD" \
  test

local_pg_psql "$TRON_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='TRON' and status='CREDITED'
      and asset_symbol in ('TRX','USDT','USDC')) <> 8 then
    raise exception 'expected eight credited TRX/USDT/USDC deposits and collections';
  end if;
  if (select count(*) from withdrawal_order where chain='TRON' and status='CONFIRMED'
      and asset_symbol in ('TRX','USDT','USDC')) <> 4 then
    raise exception 'expected four confirmed TRX/USDT/USDC withdrawals';
  end if;
  if (select count(*) from collection_record where chain='TRON' and status='CONFIRMED'
      and asset_symbol in ('TRX','USDT','USDC')) <> 3 then
    raise exception 'expected three confirmed TRX/USDT/USDC collections';
  end if;
  if (select count(*) from gas_topup_task where chain='TRON' and status='CONFIRMED') <> 4 then
    raise exception 'expected four confirmed TRC20 gas top-ups';
  end if;
  if exists (select 1 from ledger_balance where chain='TRON'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative TRON ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='TRON' and locked_balance <> 0) then
    raise exception 'non-zero locked TRON balance remains';
  end if;
end
\$\$;"

printf 'TRON PASS local TRX/USDT/USDC deposit, replay, withdrawal, collection, gas, and ledger audit\n'
