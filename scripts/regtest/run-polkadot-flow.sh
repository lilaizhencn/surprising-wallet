#!/usr/bin/env bash
set -euo pipefail

DOT_FLOW_SOURCE_ROOT=$(git rev-parse --show-toplevel)
DOT_FLOW_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-dot-build.XXXXXX)
DOT_FLOW_DB="surprising-wallet-dot-flow-$$"
DOT_FLOW_NATIVE="surprising-wallet-dot-native-$$"
DOT_FLOW_ASSET="surprising-wallet-dot-assethub-$$"
DOT_FLOW_RUNTIME="surprising-wallet-dot-runtime-$$"
DOT_FLOW_SPEC_CACHE="surprising-wallet-dot-assethub-v1240"
DOT_FLOW_SPEC_FILE="/spec/asset-hub-westend-dev-v1.24.0-raw.json"
DOT_FLOW_RUNTIME_IMAGE="surprising-wallet/polkadot-runtime-service:flow"
DOT_FLOW_DB_PORT=${DOT_FLOW_DB_PORT:-55444}
DOT_FLOW_NATIVE_PORT=${DOT_FLOW_NATIVE_PORT:-19944}
DOT_FLOW_ASSET_PORT=${DOT_FLOW_ASSET_PORT:-19945}
DOT_FLOW_RUNTIME_PORT=${DOT_FLOW_RUNTIME_PORT:-18787}
DOT_FLOW_API_KEY="local-dot-runtime-flow"
DOT_FLOW_HOT_ADDRESS="5HiW9iboC5iL7eD95tMffYwwtxnunwNP7UaX5tjDWMQHW1xv"
DOT_FLOW_SOURCE_ADDRESS="5G2juBuArdx3uL6QvQEvuMdbu6JE93eoRQE2yw2c9cQijkeW"
DOT_FLOW_USER_ADDRESS="5GWcyCUmgVc8MRSZMF1CikX3bm5wbzuwe8gxN3LM7vfvdsQe"
DOT_FLOW_EXTERNAL_ADDRESS="5DxwtdZkatUKsjMUafscZ1yKLTksRfD74nH3GGBbM8WCEYuB"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$status" != 0 && "${DOT_FLOW_KEEP_ON_FAILURE:-false}" == true ]]; then
    printf 'Polkadot failure resources kept: native=%s asset=%s runtime=%s db=%s build=%s\n' \
      "$DOT_FLOW_NATIVE" "$DOT_FLOW_ASSET" "$DOT_FLOW_RUNTIME" "$DOT_FLOW_DB" \
      "$DOT_FLOW_BUILD_ROOT" >&2
    exit "$status"
  fi
  docker rm -f "$DOT_FLOW_RUNTIME" "$DOT_FLOW_ASSET" "$DOT_FLOW_NATIVE" "$DOT_FLOW_DB" \
    >/dev/null 2>&1 || true
  if [[ "$DOT_FLOW_BUILD_ROOT" == /tmp/surprising-wallet-dot-build.* ]]; then
    rm -rf "$DOT_FLOW_BUILD_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git jq mvn rsync sed; do
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
  --exclude=/evm-fork/artifacts \
  --exclude=logs \
  "$DOT_FLOW_SOURCE_ROOT/" "$DOT_FLOW_BUILD_ROOT/"

docker info >/dev/null
docker volume create "$DOT_FLOW_SPEC_CACHE" >/dev/null
if ! docker run --rm -v "${DOT_FLOW_SPEC_CACHE}:/spec" alpine:3.20 \
    test -s "$DOT_FLOW_SPEC_FILE"; then
  printf 'Building cached Asset Hub raw chain spec; first run can take several minutes...\n'
  docker run --rm -v "${DOT_FLOW_SPEC_CACHE}:/spec" \
    --entrypoint sh parity/polkadot-parachain:v1.24.0 -lc \
    "/usr/local/bin/polkadot-parachain build-spec \
       --chain asset-hub-westend-dev --disable-default-bootnode --raw \
       > ${DOT_FLOW_SPEC_FILE}.tmp && mv ${DOT_FLOW_SPEC_FILE}.tmp ${DOT_FLOW_SPEC_FILE}"
fi

docker run -d --name "$DOT_FLOW_DB" \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -e POSTGRES_DB=wallet \
  -p "127.0.0.1:${DOT_FLOW_DB_PORT}:5432" \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$DOT_FLOW_DB" psql -U wallet -d wallet -Atqc 'select 1' >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'Polkadot integration PostgreSQL did not become ready\n' >&2
    exit 1
  fi
  sleep 1
done

sed '/^SET transaction_timeout = /d' "$DOT_FLOW_BUILD_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
  | docker exec -i "$DOT_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q
docker exec -i "$DOT_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q <<SQL
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(decode(repeat('11', 32), 'hex'), 'base64'),
     encode(decode(repeat('22', 32), 'hex'), 'base64'),
     encode(decode(repeat('33', 32), 'hex'), 'base64'),
     encode(decode('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f', 'hex'), 'base64'),
     'polkadot-devnet-test');
UPDATE chain_profile SET enabled = false WHERE chain = 'DOT';
UPDATE chain_profile
   SET enabled = true, scan_enabled = true, withdraw_enabled = true,
       collection_enabled = true, transfer_enabled = true,
       deposit_confirmations = 1, withdraw_confirmations = 1,
       scan_batch_size = 100, scan_start_height = 0, scan_max_blocks_per_run = 100
 WHERE chain = 'DOT' AND network = 'westend';
UPDATE chain_asset SET decimals = 10, active = true WHERE chain = 'DOT' AND symbol = 'DOT';
UPDATE token_config SET enabled = false WHERE chain = 'DOT';
UPDATE chain_rpc_node SET enabled = false WHERE chain = 'DOT';
UPDATE chain_rpc_node
   SET rpc_url = 'ws://host.docker.internal:${DOT_FLOW_NATIVE_PORT}',
       min_request_interval_ms = 0, enabled = true, updated_at = now()
 WHERE chain = 'DOT' AND network = 'westend' AND environment = 'dev'
   AND purpose = 'rpc' AND node_label = 'polkadot-westend-ws';
UPDATE chain_rpc_node
   SET rpc_url = 'ws://host.docker.internal:${DOT_FLOW_ASSET_PORT}',
       min_request_interval_ms = 0, enabled = true, updated_at = now()
 WHERE chain = 'DOT' AND network = 'westend' AND environment = 'dev'
   AND purpose = 'asset_rpc' AND node_label = 'polkadot-westend-assethub-ws';
UPDATE chain_rpc_node
   SET rpc_url = 'http://127.0.0.1:${DOT_FLOW_RUNTIME_PORT}',
       auth_type = 'bearer', api_key = '${DOT_FLOW_API_KEY}',
       min_request_interval_ms = 0, enabled = true, updated_at = now()
 WHERE chain = 'DOT' AND network = 'westend' AND environment = 'dev'
   AND purpose = 'runtime' AND node_label = 'local-polkadot-runtime-service';
UPDATE wallet_system_config SET config_value = '20000000000', enabled = true
 WHERE config_key = 'dot.asset_hub.min_sender_gas.planck';
UPDATE wallet_system_config SET config_value = '100000000000', enabled = true
 WHERE config_key = 'dot.asset_hub.token.gas_topup.planck';
SQL

docker run -d --name "$DOT_FLOW_NATIVE" \
  -p "127.0.0.1:${DOT_FLOW_NATIVE_PORT}:9944" \
  parity/polkadot:v1.24.0 \
  --dev --tmp --unsafe-rpc-external --rpc-cors=all --no-telemetry --no-prometheus \
  >/dev/null
docker run -d --name "$DOT_FLOW_ASSET" \
  -p "127.0.0.1:${DOT_FLOW_ASSET_PORT}:9944" \
  -v "${DOT_FLOW_SPEC_CACHE}:/spec:ro" \
  parity/polkadot-omni-node:v1.24.0 \
  --chain "$DOT_FLOW_SPEC_FILE" --dev-block-time 1000 --alice --tmp \
  --unsafe-force-node-key-generation --unsafe-rpc-external --rpc-cors=all \
  --no-hardware-benchmarks --no-prometheus \
  >/dev/null

for port in "$DOT_FLOW_NATIVE_PORT" "$DOT_FLOW_ASSET_PORT"; do
  for attempt in $(seq 1 180); do
    height=$(curl -fsS --max-time 2 -H 'content-type: application/json' \
      --data '{"jsonrpc":"2.0","id":1,"method":"chain_getHeader","params":[]}' \
      "http://127.0.0.1:${port}" 2>/dev/null | jq -r '.result.number // empty' || true)
    if [[ -n "$height" && "$height" != "0x0" ]]; then
      break
    fi
    if [[ "$attempt" == 180 ]]; then
      printf 'Polkadot node on port %s did not become ready\n' "$port" >&2
      docker logs "$DOT_FLOW_NATIVE" >&2 || true
      docker logs "$DOT_FLOW_ASSET" >&2 || true
      exit 1
    fi
    sleep 1
  done
done

docker build -t "$DOT_FLOW_RUNTIME_IMAGE" \
  "$DOT_FLOW_BUILD_ROOT/services/polkadot-runtime-service" >/dev/null
docker run -d --name "$DOT_FLOW_RUNTIME" \
  --add-host host.docker.internal:host-gateway \
  -e POLKADOT_RUNTIME_API_KEY="$DOT_FLOW_API_KEY" \
  -e POLKADOT_RUNTIME_DEV_MODE=true \
  -e POLKADOT_RUNTIME_HOST=0.0.0.0 \
  -e POLKADOT_RUNTIME_PORT=8787 \
  -p "127.0.0.1:${DOT_FLOW_RUNTIME_PORT}:8787" \
  "$DOT_FLOW_RUNTIME_IMAGE" >/dev/null

for attempt in $(seq 1 90); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${DOT_FLOW_RUNTIME_PORT}/health" >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 90 ]]; then
    printf 'Polkadot runtime service did not become ready\n' >&2
    docker logs "$DOT_FLOW_RUNTIME" >&2
    exit 1
  fi
  sleep 1
done

dev_fund() {
  local port=$1
  local address=$2
  local amount=$3
  local response
  response=$(curl -fsS --max-time 150 \
    -H "Authorization: Bearer ${DOT_FLOW_API_KEY}" \
    -H 'content-type: application/json' \
    --data "{\"rpcUrl\":\"ws://host.docker.internal:${port}\",\"ss58Prefix\":42,\"to\":\"${address}\",\"amountPlanck\":\"${amount}\"}" \
    "http://127.0.0.1:${DOT_FLOW_RUNTIME_PORT}/v1/polkadot/dev-fund")
  if [[ $(jq -r '.ok' <<<"$response") != true ]]; then
    printf 'Polkadot dev funding failed: %s\n' "$response" >&2
    exit 1
  fi
}

dev_fund "$DOT_FLOW_NATIVE_PORT" "$DOT_FLOW_SOURCE_ADDRESS" 20000000000000
dev_fund "$DOT_FLOW_NATIVE_PORT" "$DOT_FLOW_HOT_ADDRESS" 10000000000000
dev_fund "$DOT_FLOW_ASSET_PORT" "$DOT_FLOW_SOURCE_ADDRESS" 100000000000000
dev_fund "$DOT_FLOW_ASSET_PORT" "$DOT_FLOW_HOT_ADDRESS" 100000000000000
dev_fund "$DOT_FLOW_ASSET_PORT" "$DOT_FLOW_USER_ADDRESS" 10000000000
dev_fund "$DOT_FLOW_ASSET_PORT" "$DOT_FLOW_EXTERNAL_ADDRESS" 10000000000

mvn -f "$DOT_FLOW_BUILD_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=PolkadotAddressGenerationTest,PolkadotRuntimeClientTest,PolkadotDepositScannerTest,PolkadotTransactionServiceTest,PolkadotDevnetFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpolkadot.devnet.flow.enabled=true \
  -Dpolkadot.db.url="jdbc:postgresql://127.0.0.1:${DOT_FLOW_DB_PORT}/wallet" \
  -Dpolkadot.db.user=wallet \
  -Dpolkadot.db.password=wallet \
  test

docker exec "$DOT_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='DOT' and status='CREDITED'
      and asset_symbol in ('DOT','USDC','USDT')) < 3 then
    raise exception 'expected credited DOT, USDC, and USDT deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='DOT' and status='CONFIRMED'
      and asset_symbol in ('DOT','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed DOT, USDC, and USDT withdrawals';
  end if;
  if (select count(*) from collection_record where chain='DOT' and status='CONFIRMED'
      and asset_symbol in ('DOT','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed DOT, USDC, and USDT collections';
  end if;
  if exists (select 1 from ledger_balance where chain='DOT'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative DOT ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='DOT' and locked_balance <> 0) then
    raise exception 'non-zero locked DOT balance remains';
  end if;
end
\$\$;"

printf 'DOT PASS local Polkadot/Asset Hub DOT/USDC/USDT deposit, replay, withdrawal, collection, gas, and ledger audit\n'
