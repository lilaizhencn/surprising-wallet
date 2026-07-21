#!/usr/bin/env bash
set -euo pipefail

SUI_FLOW_ROOT=$(git rev-parse --show-toplevel)
SUI_FLOW_TMP=$(mktemp -d -t surprising-sui-flow.XXXXXX)
SUI_FLOW_DB_CONTAINER="surprising-wallet-sui-flow-$$"
SUI_FLOW_DB_PORT=${SUI_FLOW_DB_PORT:-55439}
SUI_FLOW_NODE_PID=""
SUI_TEST_MASTER_SEED=${SUI_TEST_MASTER_SEED:-000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$SUI_FLOW_NODE_PID" ]] && kill -0 "$SUI_FLOW_NODE_PID" 2>/dev/null; then
    kill "$SUI_FLOW_NODE_PID" 2>/dev/null || true
    wait "$SUI_FLOW_NODE_PID" 2>/dev/null || true
  fi
  docker rm -f "$SUI_FLOW_DB_CONTAINER" >/dev/null 2>&1 || true
  if [[ "$SUI_FLOW_TMP" == *"/surprising-sui-flow."* ]] && [[ -d "$SUI_FLOW_TMP" ]]; then
    trash "$SUI_FLOW_TMP"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl docker git jq lsof mvn sed sui trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

docker info >/dev/null
if curl -fsS --max-time 2 http://127.0.0.1:9123/ >/dev/null 2>&1; then
  printf 'port 9123 is already serving a Sui faucet; stop it before running this isolated test\n' >&2
  exit 1
fi
if lsof -nP -iTCP:9000 -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'port 9000 is already in use; stop the existing service before running this isolated test\n' >&2
  exit 1
fi

docker run -d --name "$SUI_FLOW_DB_CONTAINER" \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -e POSTGRES_DB=wallet \
  -p "127.0.0.1:${SUI_FLOW_DB_PORT}:5432" \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$SUI_FLOW_DB_CONTAINER" pg_isready -U wallet -d wallet >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'Sui integration PostgreSQL did not become ready\n' >&2
    exit 1
  fi
  sleep 1
done

docker exec "$SUI_FLOW_DB_CONTAINER" createdb -U wallet sui_indexer
sed '/^SET transaction_timeout = /d' "$SUI_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql" \
  | docker exec -i "$SUI_FLOW_DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -q

mkdir -p "$SUI_FLOW_TMP/network"
sui genesis -f --with-faucet --working-dir "$SUI_FLOW_TMP/network" >/dev/null
sui start \
  --network.config "$SUI_FLOW_TMP/network" \
  --with-faucet=127.0.0.1:9123 \
  --fullnode-rpc-port 9000 \
  --with-indexer="postgres://wallet:wallet@127.0.0.1:${SUI_FLOW_DB_PORT}/sui_indexer" \
  >"$SUI_FLOW_TMP/sui.log" 2>&1 &
SUI_FLOW_NODE_PID=$!

for attempt in $(seq 1 120); do
  if curl -fsS --max-time 2 http://127.0.0.1:9123/ >/dev/null 2>&1 \
      && sui client --client.config "$SUI_FLOW_TMP/network/client.yaml" gas >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$SUI_FLOW_NODE_PID" 2>/dev/null; then
    printf 'Sui local network stopped before becoming ready; log: %s\n' "$SUI_FLOW_TMP/sui.log" >&2
    exit 1
  fi
  if [[ "$attempt" == 120 ]]; then
    printf 'Sui local network did not become ready in 120 seconds; log: %s\n' "$SUI_FLOW_TMP/sui.log" >&2
    exit 1
  fi
  sleep 1
done

SUI_FLOW_PUBLISH_JSON=$(sui client \
  --client.config "$SUI_FLOW_TMP/network/client.yaml" \
  test-publish "$SUI_FLOW_ROOT/infra/sui/mock-coin" \
  --build-env testnet \
  --pubfile-path "$SUI_FLOW_TMP/Pub.localnet.toml" \
  --gas-budget 200000000 \
  --quiet \
  --json)
SUI_FLOW_PACKAGE=$(jq -er \
  '.objectChanges[] | select(.type == "published") | .packageId' \
  <<<"$SUI_FLOW_PUBLISH_JSON")
SUI_FLOW_COIN_TYPE="${SUI_FLOW_PACKAGE}::usdc::USDC"
SUI_FLOW_TREASURY=$(jq -er --arg coin_type "$SUI_FLOW_COIN_TYPE" \
  '.objectChanges[]
   | select(.type == "created")
   | select(.objectType | startswith("0x2::coin::TreasuryCap<"))
   | select(.objectType | contains($coin_type))
   | .objectId' <<<"$SUI_FLOW_PUBLISH_JSON")

SW_ED25519_SEED="$SUI_TEST_MASTER_SEED" \
SUI_DB_URL="jdbc:postgresql://127.0.0.1:${SUI_FLOW_DB_PORT}/wallet" \
SUI_DB_USER=wallet \
SUI_DB_PASSWORD=wallet \
SUI_GRPC_ENDPOINT=http://127.0.0.1:9000 \
SUI_FAUCET_URL=http://127.0.0.1:9123/v2/gas \
SUI_CLIENT_CONFIG="$SUI_FLOW_TMP/network/client.yaml" \
SUI_MOCK_PACKAGE_ID="$SUI_FLOW_PACKAGE" \
SUI_MOCK_TREASURY_ID="$SUI_FLOW_TREASURY" \
mvn -f "$SUI_FLOW_ROOT/pom.xml" \
  -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=SuiPtbTransactionBuilderTest,SuiGrpcReadIntegrationTest,SuiLiveNativeFlowIntegrationTest,SuiLiveTokenFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsui.grpc.live.enabled=true \
  -Dsui.live.enabled=true \
  -Dsui.token.live.enabled=true \
  test

docker exec "$SUI_FLOW_DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U wallet -d wallet -Atqc "
do \$\$
begin
  if (select count(*) from deposit_record where chain='SUI' and status='CREDITED' and asset_symbol in ('SUI','USDC')) <> 2 then
    raise exception 'expected one credited SUI deposit and one credited USDC deposit';
  end if;
  if (select count(*) from withdrawal_order where chain='SUI' and status='CONFIRMED' and asset_symbol in ('SUI','USDC')) <> 2 then
    raise exception 'expected one confirmed SUI withdrawal and one confirmed USDC withdrawal';
  end if;
  if (select count(*) from collection_record where chain='SUI' and status='CONFIRMED' and asset_symbol in ('SUI','USDC')) <> 2 then
    raise exception 'expected one confirmed SUI collection and one confirmed USDC collection';
  end if;
  if exists (select 1 from ledger_balance where chain='SUI' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative Sui ledger balance detected';
  end if;
  if exists (select 1 from ledger_balance where chain='SUI' and locked_balance <> 0) then
    raise exception 'non-zero locked Sui balance remains';
  end if;
end
\$\$;"

printf 'SUI PASS gRPC, SUI/USDC deposit, replay, withdrawal, collection, exact token balances, and ledger audit\n'
