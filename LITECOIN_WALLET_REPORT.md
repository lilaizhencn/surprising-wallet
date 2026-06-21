# Litecoin Wallet Report

Generated: 2026-06-21 07:50 Asia/Shanghai.

## Overall Result

- Litecoin implementation is code-complete for the current BTC-like P2WSH UTXO architecture.
- Local unit/regression tests passed.
- Full Maven build passed.
- Three runnable services started successfully with `--spring.profiles.active=test`.
- Live Litecoin testnet deposit/withdraw/collection is blocked by missing funded Litecoin testnet Core-compatible JSON-RPC.
- Commit was not created because the live Litecoin testnet flow gate is not satisfied.
- Push: no.

## Design Summary

- Litecoin is isolated as `ChainType.LTC` and `CurrencyEnum.LTC`.
- Runtime legacy currency id is `24` to avoid the historical `CurrencyEnum`/legacy id conflict with ETH.
- HD derivation uses BIP44 coin type `2` through `CurrencyEnum#getBip44CoinType()`.
- BTC runtime id, BTC witness structure, BTC multisig script shape, and BTC signing queues were not changed.
- Existing BTC-like infrastructure is reused only through chain-specific network parameters and fee policy.

## Modified Files

- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/AbstractBatchWithdrawJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/config/PubKeyConfig.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/AbstractBtcLikeWallet.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/BlockchainAdapterRegistryTest.java`
- `backendservices/wallet-sig1/src/main/java/com/surprising/wallet/sig/first/service/AbstractBtcLikeFirstSign.java`
- `backendservices/wallet-sig2/src/main/java/com/surprising/wallet/sig/second/BipNodeUtil.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/ChainType.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/currency/CurrencyEnum.java`
- `multi-chain-wallet-schema.sql`
- `surprising-wallet-init-pgsql.sql`
- `regression-report.md`

## New Files

- `LITECOIN_WALLET_REPORT.md`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/deposit/ScanLtcBlockJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/LtcCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/BatchLtcWithdrawJob.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ltc/LitecoinChainAdapter.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/impl/LtcWallet.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/ltc/LitecoinAddressGenerationTest.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/ltc/LitecoinFeeEstimatorTest.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/ltc/LitecoinLiveFlowIntegrationTest.java`
- `backendservices/wallet-sig1/src/main/java/com/surprising/wallet/sig/first/service/LtcFirstSignService.java`
- `backendservices/wallet-sig2/src/main/java/com/surprising/wallet/sig/second/impl/LtcSecondSignService.java`
- `currency-sdks/bitcoin-sdk/src/main/java/com/surprising/wallet/sdk/bitcoinj/litecoin/LitecoinFeePolicy.java`
- `currency-sdks/bitcoin-sdk/src/main/java/com/surprising/wallet/sdk/bitcoinj/litecoin/LitecoinNetwork.java`
- `currency-sdks/bitcoin-sdk/src/main/java/com/surprising/wallet/sdk/bitcoinj/litecoin/LitecoinNetworkParameters.java`
- `currency-sdks/bitcoin-sdk/src/test/java/sdk/core/LitecoinNetworkParamsTest.java`
- `currency-sdks/wallet-client/src/main/java/com/surprising/wallet/client/command/LtcCommand.java`

## Deleted Files

- None.

## Database Changes

- Added LTC native asset row in `chain_asset`.
- Added `ltc_address`.
- Added `ltc_utxo_transaction`.
- Added `ltc_withdraw_record`.
- Added `ltc_withdraw_transaction`.
- Added `best_block_height` row for currency `24`.
- Added `currency_balance` row for currency `24`.
- Full init script now creates LTC tables with independent primary/unique constraints.
- Incremental schema now uses independent LTC constraint names and indexes.
- Local execution result: `wallet` role failed DDL with `permission denied for schema public`; `atomex` admin role applied the migration successfully.

## MBG Generated Content

- No MyBatis Generator run was performed.
- No generated mapper/entity/XML files were overwritten.
- Existing sharded BTC-like tables are reused through `ShardTable` prefixes.

## Litecoin Network Parameters

- Mainnet id: `org.litecoin.production`.
- Testnet id: `org.litecoin.test`.
- Mainnet P2PKH/P2SH/Bech32: `48 / 50 / ltc`.
- Testnet P2PKH/P2SH/Bech32: `111 / 58 / tltc`.
- Decimals: `8`.
- Dust threshold: `1000` litoshi.
- Default fee rate: `2` litoshi/vbyte.
- Min/max fee rate guard: `1` to `100` litoshi/vbyte.

## Address Generation Result

- Test derivation path shape: `m/44/2/1/9001/{index}`.
- Address index 0: `tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj`.
- Address index 1: `tltc1qydpzhcujqtca9uuepts0k996jfv483xlnkf8majw0f0umaht9j6q2aktvc`.
- Both addresses use Litecoin testnet Bech32 HRP `tltc`.
- Both are rejected by Bitcoin TestNet3 address parsing.

## Deposit Txid

- Blocked.
- Reason: no funded Litecoin testnet JSON-RPC endpoint and no testnet LTC deposit txid were available.

## Withdraw Txid

- Blocked.
- Reason: live withdrawal requires funded LTC UTXO and a Core-compatible Litecoin testnet RPC endpoint for `sendrawtransaction`.

## Collection Txid

- Blocked.
- Reason: live collection requires user-address LTC UTXOs and a Core-compatible Litecoin testnet RPC endpoint.

## UTXO State Result

- Local schema has `ltc_utxo_transaction` with primary key and unique `(tx_id, seq)`.
- UTXO live state was not created because live deposit is blocked.

## Fee And Dust Result

- `LitecoinFeePolicy` clamps fee rate below min and above max.
- `LitecoinChainAdapter` native quote uses P2WSH vbytes and Litecoin fee policy.
- `LitecoinFeeEstimatorTest` passed for default and max-clamped fee.
- `LitecoinNetworkParamsTest` passed for dust threshold and address-network isolation.

## Record Results

- `deposit_record`: not populated for LTC live flow because deposit is blocked.
- `withdrawal_order`: not populated for LTC live flow because withdrawal is blocked.
- `collection_record`: not populated for LTC live flow because collection is blocked.
- Legacy BTC-like LTC tables were created and verified locally.

## Ledger Balance Result

- `currency_balance` row for currency `24` exists with balance `0.00000000`.
- Ledger live reconciliation is blocked until a live deposit/withdraw/collection cycle is executed.

## Idempotency Result

- DB idempotency keys are in place:
  - `ltc_utxo_transaction(tx_id, seq)`
  - `ltc_withdraw_record(withdraw_id)`
  - `ltc_address(address)`
  - `ltc_address(user_id, biz, index)`
- Runtime duplicate scan/withdraw/collection live idempotency remains blocked by missing live chain flow.

## Recovery Result

- Scanner/withdraw/collection job classes compile and are configured.
- Live scanner recovery, withdrawal retry, and collection retry remain blocked by missing Litecoin testnet RPC/funds.

## Multi-User Result

- Multi-address uniqueness is covered by `LitecoinAddressGenerationTest`.
- Multi-user live deposit/withdraw/collection remains blocked by missing testnet RPC/funds.

## Testnet Validation

- Local Core RPC port check: `127.0.0.1:19332` is not listening.
- `LitecoinLiveFlowIntegrationTest`: 1 test skipped with explicit blocked reason.
- Required to unblock:
  - `LTC_TESTNET_RPC_URL`
  - `LTC_TESTNET_RPC_USER`
  - `LTC_TESTNET_RPC_PASSWORD`
  - funded Litecoin testnet UTXO for a generated `tltc1...` address.

## Commands And Results

- `mvn -q -pl currency-sdks/bitcoin-sdk -Dtest=LitecoinNetworkParamsTest test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am -Dtest=LitecoinAddressGenerationTest,LitecoinFeeEstimatorTest,BlockchainAdapterRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-server -am -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-sig1 -am -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-sig2 -am -DskipTests compile`: passed.
- `psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U atomex -d wallet -f multi-chain-wallet-schema.sql`: passed.
- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire summary after final build: 65 tests, 0 failures, 0 errors, 12 skipped.
- Redis: `PONG`.
- PostgreSQL: `select 1` passed.
- `wallet-server --spring.profiles.active=test`: started, `/actuator/health` returned `UP`.
- `wallet-sig1 --spring.profiles.active=test`: started, first-sign job active.
- `wallet-sig2 --spring.profiles.active=test`: started, second-sign job active.

## Blocked Items

- Live LTC deposit scan.
- Live LTC withdrawal.
- Live LTC collection.
- Live scanner recovery.
- Live withdrawal retry recovery.
- Live collection retry recovery.
- Live ledger-vs-chain reconciliation.

## Risks

- Current Litecoin integration uses Litecoin Core-compatible JSON-RPC. Public block-explorer APIs are not a drop-in replacement for `getblockcount`, `getblockhash`, `getblock`, `getrawtransaction`, `decoderawtransaction`, and `sendrawtransaction`.
- `wallet-server` test profile still enables BTC scanning by default; startup validation can advance BTC scan height as normal existing behavior.
- `Constants.init()` still enforces test network mode globally; production profile startup for mainnet assets needs a separate reviewed change before prod use.

## Commit

- Commit hash: not created.
- Reason: Litecoin live testnet deposit/withdraw/collection gates are blocked.
- Intended commit message after live gates pass: `feat: add litecoin wallet flow`.
- Push: no.

---

## Live Deposit Verification

Deposit scan was executed against the Alchemy Litecoin testnet RPC endpoint (`https://litecoin-testnet.g.alchemy.com/v2/...`).

### Results

| Item | Value |
|------|-------|
| Deposit txid | `24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f` |
| Block | 4773130 |
| Amount | 0.01 tLTC |
| Address | `tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj` |
| Scan detected | yes |
| UTXO inserted | yes |
| user_asset credited | yes, 0.01 LTC |
| Confirmations at scan | 23 |
| Best block at scan | 4773150+ |
| RPC provider | Alchemy Litecoin testnet |
| Dust threshold | 1000 litoshi |
| Default fee rate | 2 litoshi/vbyte |

### Idempotency

- UTXO insert uses `batchAddOnDuplicateKey` (ON CONFLICT DO NOTHING for `(tx_id, seq)`).
- `creditDepositIfNeeded` checks the `credited` flag before applying credit.
- Restarting the scanner did NOT produce a duplicate credit.
- Rescanning the same block after already processing it does NOT produce duplicate UTXO records.

### Remaining Blocked Items

- **LTC live withdrawal**: blocked. Withdrawal requires the multisig infrastructure (wallet_multisig_config, hot_wallet_address, sig1/sig2 services running concurrently).
- **LTC live collection**: blocked for the same reason as withdrawal.
- **Scanner recovery scoped test**: skipped due to limited live node session time.

### Node Connectivity

- `getblockcount`: 4773150+
- `getblockhash`: returns valid hash
- `getblock`: returns full block with txids
- `getrawtransaction` (verbose): returns decoded tx with vout, scriptPubKey, addresses, confirmations
- `sendrawtransaction`: not tested (no withdrawal was triggered)
- Basic Auth: not used (Alchemy uses URL-based API key)

### LTC RPC Config Notes

Alchemy Litecoin testnet uses URL-based API key authentication. The `RpcCommandProcessor.buildRpcClient` method was modified to skip the `Authorization: Basic` header when both `username` and `password` are empty. This change is in `currency-sdks/wallet-client/src/main/java/com/surprising/wallet/client/RpcCommandProcessor.java`.
