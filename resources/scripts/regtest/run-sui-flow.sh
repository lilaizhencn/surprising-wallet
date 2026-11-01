#!/usr/bin/env bash
set -euo pipefail
umask 077

# Reuse an isolated Sui validator/fullnode ledger. Never start an auxiliary indexer database.
SUI_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_SUI_TEST_RPC_URL:?Set the isolated loopback Sui fullnode URL}"
: "${MPC_SUI_TEST_FAUCET_URL:?Set the isolated loopback Sui faucet URL}"
: "${MPC_SUI_TEST_COIN_FIXTURE:?Set the protected local USDT/USDC fixture descriptor}"
export MPC_SUI_TEST_RPC_URL MPC_SUI_TEST_FAUCET_URL MPC_SUI_TEST_COIN_FIXTURE
exec bash "$SUI_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
