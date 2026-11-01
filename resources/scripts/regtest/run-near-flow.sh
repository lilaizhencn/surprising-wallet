#!/usr/bin/env bash
set -euo pipefail
umask 077

# Reuse an isolated official NEAR ledger; the external fixture owns only its test administrator key.
NEAR_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_NEAR_TEST_RPC_URL:?Set the isolated loopback NEAR RPC URL}"
: "${MPC_NEAR_TEST_FIXTURE:?Set the protected local NEAR/USDT/USDC fixture descriptor}"
export MPC_NEAR_TEST_RPC_URL MPC_NEAR_TEST_FIXTURE
exec bash "$NEAR_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
