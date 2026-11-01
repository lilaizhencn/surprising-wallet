#!/usr/bin/env bash
set -euo pipefail
umask 077

# Reuse isolated official relay/Asset Hub archive ledgers and a public-only runtime gateway.
DOT_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_DOT_TEST_FIXTURE:?Set the protected isolated DOT/USDT/USDC administrator fixture descriptor}"
export MPC_DOT_TEST_FIXTURE
exec bash "$DOT_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
