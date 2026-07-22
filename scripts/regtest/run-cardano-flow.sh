#!/usr/bin/env bash
set -euo pipefail

CARDANO_FLOW_SOURCE_ROOT=$(git rev-parse --show-toplevel)
CARDANO_FLOW_BUILD_ROOT=$(mktemp -d /tmp/surprising-wallet-cardano-build.XXXXXX)
CARDANO_FLOW_ROOT="$CARDANO_FLOW_BUILD_ROOT"
CARDANO_FLOW_DB="surprising-wallet-cardano-flow-$$"
CARDANO_FLOW_NODE="surprising-wallet-cardano-devnet-$$"
CARDANO_FLOW_CACHE="surprising-wallet-yaci-devkit-v0106-store201"
CARDANO_FLOW_DB_PORT=${CARDANO_FLOW_DB_PORT:-55443}
CARDANO_FLOW_STORE_PORT=${CARDANO_FLOW_STORE_PORT:-18081}
CARDANO_FLOW_ADMIN_PORT=${CARDANO_FLOW_ADMIN_PORT:-11001}
CARDANO_FLOW_SOURCE_ADDRESS=addr_test1vqsrxplxdjgghsycjeshnw0eyhuw5p0tdg5hmy8r79p2hmcsv6z9j

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$status" != 0 && "${CARDANO_FLOW_KEEP_ON_FAILURE:-false}" == true ]]; then
    printf 'Cardano failure resources kept: node=%s db=%s build=%s\n' \
      "$CARDANO_FLOW_NODE" "$CARDANO_FLOW_DB" "$CARDANO_FLOW_BUILD_ROOT" >&2
    exit "$status"
  fi
  docker exec "$CARDANO_FLOW_NODE" bash -lc \
    'rm -rf /root/.yaci-cli/local-clusters/default /root/.yaci-cli/pids /root/.yaci-cli/yaci-cli.pid' \
    >/dev/null 2>&1 || true
  docker rm -f "$CARDANO_FLOW_NODE" "$CARDANO_FLOW_DB" >/dev/null 2>&1 || true
  if [[ "$CARDANO_FLOW_BUILD_ROOT" == /tmp/surprising-wallet-cardano-build.* ]]; then
    rm -rf "$CARDANO_FLOW_BUILD_ROOT"
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
  "$CARDANO_FLOW_SOURCE_ROOT/" "$CARDANO_FLOW_BUILD_ROOT/"

docker info >/dev/null
docker volume create "$CARDANO_FLOW_CACHE" >/dev/null
docker run -d --name "$CARDANO_FLOW_DB" \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -e POSTGRES_DB=wallet \
  -p "127.0.0.1:${CARDANO_FLOW_DB_PORT}:5432" \
  postgres:16-alpine >/dev/null

ready_checks=0
for attempt in $(seq 1 60); do
  if docker exec "$CARDANO_FLOW_DB" psql -U wallet -d wallet -Atqc 'select 1' >/dev/null 2>&1; then
    ready_checks=$((ready_checks + 1))
    if [[ "$ready_checks" == 3 ]]; then
      break
    fi
  else
    ready_checks=0
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'Cardano integration PostgreSQL did not become ready\n' >&2
    exit 1
  fi
  sleep 1
done

sed '/^SET transaction_timeout = /d' "$CARDANO_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
  | docker exec -i "$CARDANO_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q
docker exec -i "$CARDANO_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q <<SQL
INSERT INTO wallet_key_config
    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
VALUES
    (1,
     encode(decode(repeat('11', 32), 'hex'), 'base64'),
     encode(decode(repeat('22', 32), 'hex'), 'base64'),
     encode(decode(repeat('33', 32), 'hex'), 'base64'),
     encode(decode('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f', 'hex'), 'base64'),
     'cardano-devnet-test');
UPDATE chain_profile SET enabled = false WHERE chain = 'ADA';
UPDATE chain_profile
   SET enabled = true, scan_enabled = true, withdraw_enabled = true,
       collection_enabled = true, transfer_enabled = true,
       deposit_confirmations = 1, withdraw_confirmations = 1,
       scan_batch_size = 100, scan_start_height = 0, scan_max_blocks_per_run = 100
 WHERE chain = 'ADA' AND network = 'preprod';
UPDATE token_config SET enabled = false WHERE chain = 'ADA';
UPDATE chain_rpc_node SET enabled = false WHERE chain = 'ADA';
UPDATE chain_rpc_node
   SET rpc_url = 'http://127.0.0.1:${CARDANO_FLOW_STORE_PORT}/api/v1/',
       api_key = 'local-devnet', min_request_interval_ms = 0, enabled = true,
       updated_at = now()
 WHERE chain = 'ADA' AND network = 'preprod' AND environment = 'dev' AND purpose = 'rpc';
SQL

docker run -d --name "$CARDANO_FLOW_NODE" \
  -p "127.0.0.1:${CARDANO_FLOW_STORE_PORT}:8080" \
  -p "127.0.0.1:${CARDANO_FLOW_ADMIN_PORT}:10000" \
  -v "${CARDANO_FLOW_CACHE}:/root/.yaci-cli" \
  node:22-trixie bash -lc \
  "npm install -g @bloxbean/yaci-devkit@0.10.6 >/tmp/npm.log 2>&1 && \
   sed -i \
     -e 's/yaci.store.tag=rel-native-2.0.0-beta1-devkit/yaci.store.tag=rel-native-2.0.1-devkit/' \
     -e 's/yaci.store.version=2.0.0-beta1-devkit/yaci.store.version=2.0.1-devkit/' \
     -e 's/yaci.store.jar.version=2.0.0-beta1/yaci.store.jar.version=2.0.1/' \
     /usr/local/lib/node_modules/@bloxbean/yaci-devkit/node_modules/@bloxbean/yaci-devkit-linux-x64/config/download.properties && \
   if [[ ! -x /root/.yaci-cli/cardano-node/bin/cardano-node || \
         ! -x /root/.yaci-cli/components/store/yaci-store-n2c ]]; then \
     yaci-devkit download || true; \
   fi && \
   test -x /root/.yaci-cli/cardano-node/bin/cardano-node && \
   test -x /root/.yaci-cli/components/store/yaci-store-n2c && \
   cp /root/.yaci-cli/components/store/yaci-store-n2c \
      /root/.yaci-cli/components/store/yaci-store && \
   chmod 755 /root/.yaci-cli/components/store/yaci-store && \
   rm -rf /root/.yaci-cli/local-clusters/default /root/.yaci-cli/pids /root/.yaci-cli/yaci-cli.pid && \
   exec yaci-devkit up --enable-yaci-store" \
  >/dev/null

for attempt in $(seq 1 180); do
  store_ready=false
  admin_ready=false
  if curl -fsS --max-time 2 \
      "http://127.0.0.1:${CARDANO_FLOW_STORE_PORT}/api/v1/blocks/latest" >/dev/null 2>&1; then
    store_ready=true
  fi
  if curl -fsS --max-time 2 \
      "http://127.0.0.1:${CARDANO_FLOW_ADMIN_PORT}/v3/api-docs" >/dev/null 2>&1; then
    admin_ready=true
  fi
  if [[ "$store_ready" == true && "$admin_ready" == true ]]; then
    break
  fi
  if ! docker inspect -f '{{.State.Running}}' "$CARDANO_FLOW_NODE" 2>/dev/null | grep -q true; then
    docker logs "$CARDANO_FLOW_NODE" >&2
    exit 1
  fi
  if [[ "$attempt" == 180 ]]; then
    printf 'Cardano Yaci devnet did not become ready\n' >&2
    docker logs "$CARDANO_FLOW_NODE" >&2
    exit 1
  fi
  sleep 1
done

topup_result=$(curl -fsS --max-time 30 \
  -H 'content-type: application/json' \
  --data "{\"address\":\"${CARDANO_FLOW_SOURCE_ADDRESS}\",\"adaAmount\":100000}" \
  "http://127.0.0.1:${CARDANO_FLOW_ADMIN_PORT}/local-cluster/api/addresses/topup")
if [[ $(jq -r '.status' <<<"$topup_result") != true ]]; then
  printf 'Cardano deterministic source topup failed: %s\n' "$topup_result" >&2
  exit 1
fi

for attempt in $(seq 1 60); do
  source_lovelace=$(curl -fsS --max-time 2 \
      "http://127.0.0.1:${CARDANO_FLOW_STORE_PORT}/api/v1/addresses/${CARDANO_FLOW_SOURCE_ADDRESS}/utxos" \
      2>/dev/null \
    | jq -r '[.[].amount[] | select(.unit == "lovelace") | (.quantity | tonumber)] | add // 0' \
      2>/dev/null || true)
  if [[ "${source_lovelace:-0}" -ge 100000000000 ]]; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'Cardano source topup was not indexed\n' >&2
    exit 1
  fi
  sleep 1
done

mvn -f "$CARDANO_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=CardanoAddressGenerationTest,CardanoAssetUnitTest,CardanoBackendClientTest,CardanoDepositScannerTest,CardanoDevnetFullFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcardano.devnet.flow.enabled=true \
  -Dcardano.db.url="jdbc:postgresql://127.0.0.1:${CARDANO_FLOW_DB_PORT}/wallet" \
  -Dcardano.db.user=wallet \
  -Dcardano.db.password=wallet \
  test

docker exec "$CARDANO_FLOW_DB" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='ADA' and status='CREDITED'
      and asset_symbol in ('ADA','USDC','USDT')) < 3 then
    raise exception 'expected credited ADA, USDC, and USDT deposits';
  end if;
  if (select count(*) from withdrawal_order where chain='ADA' and status='CONFIRMED'
      and asset_symbol in ('ADA','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed ADA, USDC, and USDT withdrawals';
  end if;
  if (select count(*) from collection_record where chain='ADA' and status='CONFIRMED'
      and asset_symbol in ('ADA','USDC','USDT')) <> 3 then
    raise exception 'expected confirmed ADA, USDC, and USDT collections';
  end if;
  if exists (select 1 from ledger_balance where chain='ADA'
      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative ADA ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='ADA' and locked_balance <> 0) then
    raise exception 'non-zero locked ADA balance remains';
  end if;
  if exists (select 1 from withdrawal_order where chain='ADA' and status <> 'CONFIRMED') then
    raise exception 'unresolved ADA withdrawal remains';
  end if;
  if exists (select 1 from collection_record where chain='ADA' and status <> 'CONFIRMED') then
    raise exception 'unresolved ADA collection remains';
  end if;
end
\$\$;"

printf 'ADA PASS local Yaci devnet ADA/USDC/USDT deposit, replay, withdrawal, collection, and ledger audit\n'
