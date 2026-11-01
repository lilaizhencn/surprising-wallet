#!/usr/bin/env bash
set -euo pipefail
umask 077
command -v anvil >/dev/null || { printf 'Anvil is required for the native EVM transaction probe\n' >&2; exit 1; }

MPC_WALLET_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$MPC_WALLET_ROOT/resources/scripts/regtest/local-postgres.sh"
MPC_WALLET_DATABASE="surprising_wallet_test_mpc_$$"
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  local_pg_drop "$MPC_WALLET_DATABASE" >/dev/null 2>&1 || true
  exit "$status"
}
local_pg_require
local_pg_create "$MPC_WALLET_DATABASE"
trap cleanup EXIT INT TERM
export SW_TEST_CUSTODY_DB_URL="$(local_pg_jdbc_url "$MPC_WALLET_DATABASE")"
export SW_TEST_CUSTODY_DB_USERNAME="$REGTEST_PG_USER"
export SW_TEST_CUSTODY_DB_PASSWORD="$REGTEST_PG_PASSWORD"
mvn -q -f "$MPC_WALLET_ROOT/pom.xml" -pl wallet-api -am \
  -Dtest=MpcSigningPersistenceIntegrationTest,ArchitectureBoundaryTest,WalletRpcPolicyTest,WalletStartupValidatorTest,WalletRpcAuthenticationTest,CustodyGasServiceTest,MpcPublicAddressCodecTest,MpcEvmTransactionCodecTest,MpcUtxoTransactionCodecTest,CustodyAddressAllocationIntegrationTest,CustodyAddressRuntimeIntegrationTest,CustodyOperationsIntegrationTest,EvmSubmissionIntegrationTest,UtxoSigningSubmissionIntegrationTest,UtxoSigningPipelineIntegrationTest,BtcCollectionRbfConcurrencyIntegrationTest,BtcUtxoCollectionFlowTest,TransactionQueueSafetyTest,RbfBumpServiceTest,TronSubmissionIntegrationTest,MpcTronTransactionCodecTest,TronExecutionReceiptTest,TronAccountChainServiceTest,TronTransferRpcBoundaryTest,AccountChainWorkflowServiceTest,MpcXrpTransactionCodecTest,XrpExecutionReceiptTest,XrpSubmissionIntegrationTest,WalletTaskLeaseServiceTest,CustodyGasAccountingIntegrationTest,MpcHyperCoreActionCodecTest,HyperCoreSubmissionIntegrationTest,MpcAptosTransactionCodecTest,CardanoRpcClientTest,MpcCardanoTransactionCodecTest,CardanoExecutionReceiptTest,CardanoTransactionPersistenceIntegrationTest,CardanoSubmissionIntegrationTest,CardanoScanAnchorServiceTest,CardanoAddressPublicationIntegrationTest,CardanoDepositIntegrationTest,CardanoDepositScannerTest,AccountChainRequiredRuntimeTest,AccountChainWorkflowServiceTest,CardanoAssetUnitTest,AptosExecutionReceiptTest,AptosSubmissionIntegrationTest,MpcPolkadotTransactionCodecTest,PolkadotExecutionReceiptTest,PolkadotTransactionServiceTest,PolkadotRuntimeClientTest,PolkadotDepositScannerTest,PolkadotSubmissionIntegrationTest,MpcNearTransactionCodecTest,NearExecutionReceiptTest,NearRpcClientTest,NearDepositScannerTest,NearSubmissionIntegrationTest,NearMoneyPrecisionIntegrationTest,MpcSuiTransactionCodecTest,SuiPtbTransactionBuilderTest,ChainPublicAddressValidationTest,SuiExecutionReceiptTest,SuiDepositScannerTest,SuiSubmissionIntegrationTest,AccountChainDepositSchedulingTest,MpcTonTransactionCodecTest,TonExecutionReceiptTest,TonAddressGenerationTest,TonDepositScannerTest,TonTransactionPersistenceIntegrationTest,TonSubmissionIntegrationTest,MpcSolanaTransactionCodecTest,SolanaExecutionReceiptTest,SolanaAddressGenerationTest,SolanaDepositScannerTest,SolanaSubmissionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false "$@" test

python3 - "$MPC_WALLET_ROOT" <<'VERIFY'
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
reports = root / 'wallet-api/target/surefire-reports'
suite = ET.parse(reports / 'TEST-com.surprising.wallet.custody.MpcSigningPersistenceIntegrationTest.xml').getroot()
if any(int(suite.get(name, '0')) for name in ('failures', 'errors', 'skipped')) or int(suite.get('tests', '0')) <= 0:
    raise SystemExit('MPC persistence tests must run without failures or skips')
classpath = next((prop.get('value') for prop in suite.findall('./properties/property')
                  if prop.get('name') == 'java.class.path'), None)
if not classpath or not (root / 'wallet-api/target/test-classes/com/surprising/wallet/custody/MpcSigningNativeProbe.class').is_file():
    raise SystemExit('Compiled wallet native probe and actual reactor classpath are required')
env = os.environ.copy()
env['MPC_WALLET_TEST_CLASSPATH'] = classpath
result = subprocess.run(['bash', str(root / 'resources/infra/mpc-signer/build-and-test.sh')], env=env)
raise SystemExit(result.returncode)
VERIFY
