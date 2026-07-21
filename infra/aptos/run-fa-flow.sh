#!/usr/bin/env bash
set -euo pipefail

APTOS_FLOW_ROOT=$(git rev-parse --show-toplevel)
APTOS_FLOW_TMP=$(mktemp -d -t surprising-aptos-fa.XXXXXX)
APTOS_FLOW_DB="wallet_aptos_it_$$"
APTOS_FLOW_DB_USER=$(id -un)
APTOS_FLOW_NODE_PID=""
APTOS_TEST_MASTER_SEED=${APTOS_TEST_MASTER_SEED:-000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$APTOS_FLOW_NODE_PID" ]] && kill -0 "$APTOS_FLOW_NODE_PID" 2>/dev/null; then
    kill "$APTOS_FLOW_NODE_PID" 2>/dev/null || true
    wait "$APTOS_FLOW_NODE_PID" 2>/dev/null || true
  fi
  dropdb -h 127.0.0.1 --if-exists "$APTOS_FLOW_DB" >/dev/null 2>&1 || true
  if [[ "$APTOS_FLOW_TMP" == *"/surprising-aptos-fa."* ]] && [[ -d "$APTOS_FLOW_TMP" ]]; then
    trash "$APTOS_FLOW_TMP"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for command in aptos createdb dropdb jq mvn node openssl psql curl trash; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

if curl -fsS http://127.0.0.1:8080/v1 >/dev/null 2>&1; then
  printf 'port 8080 is already serving an Aptos node; stop it before running this isolated test\n' >&2
  exit 1
fi

aptos node run-localnet \
  --test-dir "$APTOS_FLOW_TMP/localnet" \
  --force-restart \
  --seed 8420218420218420218420218420218420218420218420218420218420218420 \
  --no-txn-stream \
  --faucet-port 18081 \
  --ready-server-listen-port 18070 \
  --assume-yes >"$APTOS_FLOW_TMP/localnet.log" 2>&1 &
APTOS_FLOW_NODE_PID=$!

for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:18070/ >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APTOS_FLOW_NODE_PID" 2>/dev/null; then
    printf 'Aptos localnet stopped before becoming ready\n' >&2
    exit 1
  fi
  if [[ "$attempt" == 60 ]]; then
    printf 'Aptos localnet did not become ready in 60 seconds\n' >&2
    exit 1
  fi
  sleep 1
done

mkdir -p "$APTOS_FLOW_TMP/admin"
APTOS_FLOW_ADMIN_SEED=$(openssl rand -hex 32)
(
  cd "$APTOS_FLOW_TMP/admin"
  aptos init \
    --network custom \
    --rest-url http://127.0.0.1:8080 \
    --faucet-url http://127.0.0.1:18081 \
    --random-seed "$APTOS_FLOW_ADMIN_SEED" \
    --profile fa-admin \
    --assume-yes </dev/null >/dev/null
)
unset APTOS_FLOW_ADMIN_SEED

APTOS_FLOW_PUBLISHER=$(awk '/account:/{print $2; exit}' "$APTOS_FLOW_TMP/admin/.aptos/config.yaml")
if [[ "$APTOS_FLOW_PUBLISHER" =~ ^[0-9a-f]{64}$ ]]; then
  APTOS_FLOW_PUBLISHER="0x$APTOS_FLOW_PUBLISHER"
fi
if [[ ! "$APTOS_FLOW_PUBLISHER" =~ ^0x[0-9a-f]{64}$ ]]; then
  printf 'failed to resolve the local FA publisher address\n' >&2
  exit 1
fi

(
  cd "$APTOS_FLOW_TMP/admin"
  aptos move publish \
    --package-dir "$APTOS_FLOW_ROOT/infra/aptos/fa-test" \
    --output-dir "$APTOS_FLOW_TMP/fa-build" \
    --named-addresses "test_fa=$APTOS_FLOW_PUBLISHER" \
    --profile fa-admin \
    --skip-fetch-latest-git-deps \
    --assume-yes >/dev/null
)

APTOS_FLOW_METADATA=$(
  cd "$APTOS_FLOW_TMP/admin"
  aptos move view \
    --function-id "$APTOS_FLOW_PUBLISHER::test_assets::metadata_addresses" \
    --profile fa-admin
)
APTOS_FLOW_USDC=$(jq -er '.Result[0]' <<<"$APTOS_FLOW_METADATA")
APTOS_FLOW_USDT=$(jq -er '.Result[1]' <<<"$APTOS_FLOW_METADATA")

APTOS_FLOW_ADDRESSES=$(APTOS_TEST_MASTER_SEED="$APTOS_TEST_MASTER_SEED" \
  node "$APTOS_FLOW_ROOT/infra/aptos/derive-test-addresses.js")
APTOS_FLOW_EXTERNAL=$(jq -er '.external' <<<"$APTOS_FLOW_ADDRESSES")
APTOS_FLOW_OWNER=$(jq -er '.owner' <<<"$APTOS_FLOW_ADDRESSES")
APTOS_FLOW_HOT=$(jq -er '.hot' <<<"$APTOS_FLOW_ADDRESSES")

for address in "$APTOS_FLOW_EXTERNAL" "$APTOS_FLOW_OWNER"; do
  aptos account fund-with-faucet \
    --account "$address" \
    --amount 1000000000 \
    --url http://127.0.0.1:8080 \
    --faucet-url http://127.0.0.1:18081 >/dev/null
done

for symbol in usdc usdt; do
  (
    cd "$APTOS_FLOW_TMP/admin"
    aptos move run \
      --function-id "$APTOS_FLOW_PUBLISHER::test_assets::mint_$symbol" \
      --args "address:$APTOS_FLOW_EXTERNAL" u64:1000000000 \
      --profile fa-admin \
      --assume-yes >/dev/null
  )
done

createdb -h 127.0.0.1 "$APTOS_FLOW_DB"
psql -h 127.0.0.1 -d "$APTOS_FLOW_DB" -q -v ON_ERROR_STOP=1 \
  -f "$APTOS_FLOW_ROOT/docs/db/surprising-wallet-init-pgsql.sql"
psql -h 127.0.0.1 -d "$APTOS_FLOW_DB" -q -v ON_ERROR_STOP=1 \
  -v usdc="$APTOS_FLOW_USDC" -v usdt="$APTOS_FLOW_USDT" <<'SQL'
update chain_profile
   set rpc_url='http://127.0.0.1:8080/v1', chain_id=4,
       deposit_confirmations=1, withdraw_confirmations=1,
       scan_enabled=true, withdraw_enabled=true,
       collection_enabled=true, transfer_enabled=true,
       updated_at=now()
 where chain='APTOS' and network='testnet';
update token_config
   set contract_address=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       contract_address_hex=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       updated_at=now()
 where chain='APTOS' and network='testnet' and symbol in ('USDC','USDT');
update chain_asset
   set contract_address=case symbol when 'USDC' then :'usdc' when 'USDT' then :'usdt' end,
       updated_at=now()
 where chain='APTOS' and symbol in ('USDC','USDT');
SQL

run_asset_test() {
  local symbol=$1
  local metadata=$2
  APTOS_DB_URL="jdbc:postgresql://127.0.0.1:5432/$APTOS_FLOW_DB" \
  APTOS_DB_USER="$APTOS_FLOW_DB_USER" \
  APTOS_RPC_URL=http://127.0.0.1:8080/v1 \
  SW_ED25519_SEED="$APTOS_TEST_MASTER_SEED" \
  APTOS_FA_SYMBOL="$symbol" \
  APTOS_FA_METADATA="$metadata" \
  mvn -q -f "$APTOS_FLOW_ROOT/pom.xml" \
    -pl backendservices/wallet-parent/wallet-service -am \
    -Dtest=AptosLiveFungibleAssetFlowIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Daptos.fa.live.enabled=true test
}

run_asset_test USDC "$APTOS_FLOW_USDC"
run_asset_test USDT "$APTOS_FLOW_USDT"

for item in "USDC|$APTOS_FLOW_USDC" "USDT|$APTOS_FLOW_USDT"; do
  symbol=${item%%|*}
  metadata=${item#*|}
  ledger_atomic=$(psql -h 127.0.0.1 -d "$APTOS_FLOW_DB" -Atqc \
    "select (sum(total_balance) * 1000000)::numeric(78,0) from ledger_balance where chain='APTOS' and asset_symbol='$symbol'")
  owner_atomic=$(curl -fsS "http://127.0.0.1:8080/v1/accounts/$APTOS_FLOW_OWNER/balance/$metadata")
  hot_atomic=$(curl -fsS "http://127.0.0.1:8080/v1/accounts/$APTOS_FLOW_HOT/balance/$metadata")
  controlled_atomic=$((owner_atomic + hot_atomic))
  if [[ "$ledger_atomic" != "$controlled_atomic" ]]; then
    printf '%s reconciliation failed: ledger=%s controlled=%s\n' \
      "$symbol" "$ledger_atomic" "$controlled_atomic" >&2
    exit 1
  fi
done

psql -h 127.0.0.1 -d "$APTOS_FLOW_DB" -v ON_ERROR_STOP=1 -Atqc "
do \$\$
begin
  if exists (select 1 from token_config where chain='APTOS' and (symbol='MUSD' or standard='APTOS_COIN')) then
    raise exception 'legacy Aptos MUSD/APTOS_COIN configuration remains';
  end if;
  if (select count(*) from deposit_record where chain='APTOS' and status='CREDITED' and asset_symbol in ('USDC','USDT')) <> 2 then
    raise exception 'expected one credited deposit for each FA';
  end if;
  if (select count(*) from withdrawal_order where chain='APTOS' and status='CONFIRMED' and asset_symbol in ('USDC','USDT')) <> 2 then
    raise exception 'expected one confirmed withdrawal for each FA';
  end if;
  if (select count(*) from collection_record where chain='APTOS' and status='CONFIRMED' and asset_symbol in ('USDC','USDT')) <> 2 then
    raise exception 'expected one confirmed collection for each FA';
  end if;
  if exists (select 1 from ledger_balance where chain='APTOS' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)) then
    raise exception 'negative Aptos ledger balance detected';
  end if;
end
\$\$;"

printf 'Aptos FA flow passed: USDC and USDT deposit, replay, withdrawal, collection, and reconciliation\n'
