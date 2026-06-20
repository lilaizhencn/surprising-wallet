# TRON Trident Integration Report

Generated: 2026-06-21 Asia/Shanghai.

## Overall

- Trident SDK selected: `io.github.tronprotocol:trident:0.11.0`.
- Trident dependency was added to `wallet-service`.
- Protobuf runtime conflict was found and fixed by making `protobuf-java` and `protobuf-java-util` `3.25.8` direct dependencies before legacy Bitcoin/TRON transitive dependencies.
- Nile gRPC connectivity test passed after the protobuf fix.
- Nile live TRX/TRC20 full flow passed on 2026-06-21.
- TRON bottom-layer implementation is present for key compatibility, address conversion, TRC20 ABI, TRC20 log decoding, TRX transaction building, TRC20 transaction building, gas decision, WAITING_GAS, and ledger reconciliation.
- Full TRX/TRC20 live deposit/withdraw/collection is now verified on Nile.
- No commit was created and nothing was pushed because the USDC TRC20 live gate is still blocked.

## Implemented Production Classes

- `TronTridentKeyFactory`: creates Trident `KeyPair` from existing Bitcoin `ECKey` private scalar.
- `TronAddressCodec`: converts TRON base58, 21-byte hex, and TRC20 topic addresses.
- `Trc20AbiCodec`: encodes `transfer(address,uint256)` and decodes TRC20 `Transfer` logs.
- `TronTridentClient`: wraps Trident `ApiWrapper` and gRPC lifecycle.
- `TronTransactionService`: builds and signs native TRX transfers.
- `TronTrc20Service`: builds and signs TRC20 triggerSmartContract transfers.
- `TronScanner`: decodes Trident `TransactionInfo.Log` TRC20 events without reusing EVM scanner logic.
- `TronGasEstimator`: calculates bounded top-up decisions and TRC20 fee estimates.
- `TronWaitingGasStateService`: creates deterministic WAITING_GAS top-up task decisions.
- `TronLedgerReconciliationService`: compares ledger and chain balances exactly.

## Bitcoin ECKey To Trident KeyPair

- Existing root key design is preserved.
- No new TRON seed, mnemonic, or derivation scheme was introduced.
- Private key normalization:
  - strips optional `0x`;
  - lowercases hex;
  - validates hex;
  - left-pads to fixed 32 bytes / 64 hex chars;
  - rejects zero or invalid key material.
- Compatibility test passed: Trident-derived TRON base58 address equals legacy `TronWalletApi.getAddress(ecKey.getPubKey())`.

## TRC20 Address And ABI

- TRON base58 address round-trip test passed.
- TRON 21-byte hex address validation passed.
- TRC20 indexed topic address decoding passed by re-adding the `41` network prefix.
- Nile live log compatibility fixed: `TransactionInfo.log.address` may contain the 20-byte contract hash, so scanner prepends `41` before token matching.
- TRC20 `transfer(address,uint256)` encoding test passed.
- TRC20 `Transfer` event decode test passed.
- Trident protobuf `TransactionInfo.Log` decode test passed.

## Gas / WAITING_GAS

- Gas top-up decision tests passed.
- Top-up is bounded by `maxGasTopup`.
- WAITING_GAS generates deterministic gas task IDs.
- Sufficient TRX does not create a top-up task.

## Ledger

- Ledger reconciliation exact-match test passed.
- Ledger mismatch delta test passed.
- New guarded ledger methods already exist in `ChainJdbcRepository` for freeze, release, and locked debit settlement.

## Database

SQL changes were added to `multi-chain-wallet-schema.sql`:

- `token_config` TRC20 fields:
  - `network`
  - `token_standard`
  - `contract_address_base58`
  - `contract_address_hex`
  - `min_deposit_amount`
  - `min_withdraw_amount`
  - `collect_threshold`
  - `gas_strategy`
  - `confirmation_required`
- New table `collection_record`.
- New table `gas_topup_task`.

Local execution result:

- Initial execution with the low-privilege `wallet` role was blocked by schema/table ownership.
- The migration was then executed successfully with the local `atomex` database role.
- Verified `token_config` has all 9 TRC20 columns.
- Verified `collection_record` and `gas_topup_task` exist.
- Verified the `wallet` role can read the new `token_config.token_standard` column.
- Verified the `wallet` role can insert into `collection_record` and `gas_topup_task` in rollback transactions, including sequence usage.
- Nile `USDT` token_config was inserted:
  - base58: `TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf`
  - hex: `41eca9bc828a3005b9a3b909f2cc5c2a54794de05f`
  - decimals: `6`

## Tests

Passed:

- `TronTridentKeyFactoryTest`
- `TronAddressCodecTest`
- `Trc20AbiEncodingTest`
- `TronScannerTest`
- `TronGasEstimatorTest`
- `TronWaitingGasStateTest`
- `TronLedgerIdempotencyTest`
- `TronNileConnectivityIntegrationTest` with `-Dtron.live.enabled=true`
- `TronLiveFullFlowIntegrationTest` with `-Dtron.live.flow.enabled=true`
- `TronAddressGenerationTest`
- `TronTrxDepositScanIntegrationTest`
- `TronTrxWithdrawIntegrationTest`
- `Trc20DepositScanIntegrationTest`
- `Trc20WithdrawIntegrationTest`
- `Trc20CollectionIntegrationTest`
- `TronGasTopupIntegrationTest`
- Root `mvn clean install -DskipTests=false`
- Startup verification for `wallet-server`, `wallet-sig1`, and `wallet-sig2`

Commands:

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service '-Dtest=com.surprising.wallet.service.chain.tron.*Test' test
```

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=com.surprising.wallet.service.chain.tron.TronNileConnectivityIntegrationTest \
  -Dtron.live.enabled=true \
  test
```

```bash
mvn clean install -DskipTests=false
```

Live flow command:

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=com.surprising.wallet.service.chain.tron.TronLiveFullFlowIntegrationTest \
  -Dtron.live.flow.enabled=true \
  test
```

Startup result:

- `wallet-server`: started on port `8002`; `/actuator/health` returned `UP`; PostgreSQL Hikari pool initialized.
- `wallet-sig1`: started on port `8004`; signing job started.
- `wallet-sig2`: started as a non-web signing process; signing job started and stayed running.
- Redis check: `PONG`.
- PostgreSQL check: `select 1` succeeded.
- Cleanup note: `wallet-server` Tomcat shutdown completed, but the BTC scan scheduler kept the JVM alive briefly; the validation process was terminated after shutdown cleanup.

`test` profile validation:

- `wallet-server --spring.profiles.active=test`: started on `8002`, `/actuator/health` returned `UP`, PostgreSQL Hikari pool initialized and shut down cleanly.
- `wallet-sig1 --spring.profiles.active=test`: started on `8004`.
- `wallet-sig2 --spring.profiles.active=test`: started as a non-web signing process.
- Cleanup completed with no listener remaining on `8002`, `8004`, or `8081`.

Final gate re-check on `2026-06-21 00:42:00 +0800`:

- `mvn -q clean install -DskipTests=false` passed with exit code `0`.
- Surefire summary after full build: `57` tests, `0` failures, `0` errors, `11` skipped.
- Redis check passed: `PONG`.
- PostgreSQL check passed: `select 1`.
- `wallet-server --spring.profiles.active=test`: started on `8002`; `/actuator/health` returned `UP`.
- `wallet-sig1 --spring.profiles.active=test`: started on `8004`; first-sign job started.
- `wallet-sig2 --spring.profiles.active=test`: non-web signing process started; second-sign job started.
- `wallet-sig2` startup printed the existing environment warning `第二次签名服务校验钱包环境 没有初始化`; process startup itself remained successful.

## Nile Live Flow

- TRX deposit: `3ada2e6ebc97d206fcf08e332d4c30699d9ea9035d91cb852bf1e5a49b7ad80c`.
- TRX withdraw 1: `6767933aac18c26d14a1e0f07047897bf30dd0e1ac0cd38bef8de0142cad27b4`.
- TRX withdraw 2: `6cf387ca07f3fb05a5ba570d92ec1f066de80ac796f43536795ed0b318cbf985`.
- TRC20 USDT deposit B: `e2b7583e00d7f097e6d5c98c4bf22850b6760b8bdc36aeee716c413a9f4a33ee`.
- TRC20 USDT deposit C: `9895fcd268408d94f28ab23b0f86f7c1b5cfb8efeba44ab37a3f7ac3a3685ede`.
- TRC20 USDT collection: `8b7d473180bb4584ccc59cf2244bd9c35a4a4f9c4b93ca5588a237e65007a43d`.
- TRC20 USDT withdraw: `c5b2a2ad1d4cfed63c884320c89a34e6dbd69cd24d176828e5a6195408c3f271`.
- Gas top-up B: `d6189681b50f23199df6845f592bfc01303a58b099d2631166a53816f9bf93f0`.
- Gas top-up C: `610111a7398087fa3be86a93ebd2f06bd398149daf0eb6f19053a83d37266ca2`.
- Detailed report: `TRON_LIVE_FLOW_REPORT.md`.

## Blocked Live Flows

- USDC TRC20 live flow remains blocked because the Nile faucet provided USDT, not USDC.
- A third-party Nile USDC contract was not accepted as verified test collateral, and no controlled mock USDC contract has been deployed through the wallet test path yet.
- Shasta was not run because Nile completed the required TRX/USDT flow.

## Risk Items

- `wallet-service` now pins protobuf full runtime to `3.25.8` for Trident compatibility; BTC/EVM regression must be re-run after this dependency change.
- Legacy `tron-sdk` remains in the project. It is not removed to avoid breaking current startup, but future work should gradually route TRON runtime jobs through Trident wrappers.
- USDC TRC20 requires either a Nile/Shasta faucet token or a deployed mock USDC contract.

## Commit

- Commit hash: not created.
- Push: no.

Suggested commit message after remaining live gates pass:

`feat: implement tron trident trc20 wallet full flow`
