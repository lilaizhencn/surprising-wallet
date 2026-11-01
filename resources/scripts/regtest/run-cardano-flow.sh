#!/usr/bin/env bash
set -euo pipefail
umask 077

# Reuse an isolated official Cardano ledger; no private-key wallet or auxiliary indexer database.
CARDANO_FLOW_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
: "${MPC_CARDANO_TEST_FIXTURE:?Set the protected isolated Cardano administrator fixture descriptor}"
export MPC_CARDANO_TEST_FIXTURE
exec bash "$CARDANO_FLOW_ROOT/resources/infra/mpc-signer/test-wallet-service.sh" "$@"
