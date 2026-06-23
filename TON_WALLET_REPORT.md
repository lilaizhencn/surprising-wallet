# TON Wallet Report

Generated: 2026-06-23 Asia/Shanghai.

## Overall Conclusion

TON native and Jetton wallet flow is implemented and verified on TON testnet.
Because public testnet USDT/USDC could not be obtained, USDT and USDC were
validated with self-deployed TON testnet mock Jetton masters. The live gate
covered native TON deposit scan, native withdrawal, native collection, mock
USDT/USDC Jetton deposit scan, Jetton withdrawal, Jetton collection, idempotency,
seqno reservation, ledger reconciliation, PostgreSQL, Redis, full Maven
regression, and three-service startup.

No secrets were committed. No push was performed.

## Key Architecture

- Chain model: Account / Message, not UTXO and not EVM logs.
- Native wallet: TON WalletV4R2.
- Token model: Jetton Master + per-owner Jetton Wallet.
- Address encoding: ton4j user-friendly testnet addresses; raw-address
  normalization is used for scanner comparisons.
- Sequence management: `account_sequence` reserves WalletV4R2 `seqno` before
  signing to avoid duplicate signed messages.
- Runtime currency id: `51`, loaded from `chain_profile`.
- BIP44 coin type: `607`, independent from runtime currency id.
- Derivation path: `m/44'/607'/{userIndex}'/0'`.
- `CurrencyEnum` and `CurrencyIds` are not TON source of truth.
- Private keys/master seed are read from external secret storage only; no key is
  generated into source or YAML.

## Addresses

- TON owner / funding address:
  `0QAZqo0OKuwLmIsJT57odv_onrMViV4mnymFhoYst7EUiGWs`
- External recipient:
  `0QC1F5dA8vlIyvUGIMiHTZUreQVNt69dXZvsNjuxFLUGOcCG`
- Hot wallet:
  `0QCOrx-PYGs7ab6r81lFm6B7t4XbmNkdeqc6Ns_bIpmnHrmE`

## Mock Jetton Contracts

- USDT mock Jetton master:
  `kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT`
- USDT user Jetton wallet:
  `kQAhc77djkdA8nwm6ndGnb4ki1ikHb3-ClCI7GTTAJjDZDnq`
- USDC mock Jetton master:
  `kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2`
- USDC user Jetton wallet:
  `kQDZmpl3A-hC_6BzNCuTVKC5mrbIWbLDsqOtraQul9BUvM_C`

Both mock masters are deployed on TON testnet and configured through
`token_config` during live validation. They are test assets only, not production
USDT/USDC.

## Live Transaction Results

- Wallet deploy tx: `already-active`
- TON native withdrawal tx:
  `6/v48yQabtNIfMH5VImFxW9xK6dO9FS+sLiqdXvRs/g=`
- TON native collection tx:
  `mU39Pf7F9Q68rYc2/5OTbNCRpqmREMxb7dy1U+kzlM8=`
- USDT master deploy tx: `already-active`
- USDT mint tx: `existing-balance`
- USDT withdrawal tx:
  `WCLKsqWSVTCd7oeVKWP4W5Ft57XQdusB/uGNuzBpxKw=`
- USDT collection tx:
  `aVTlXPR5Cw4HIaxAu+3zy9/I3b6fsQfQvdnG2EgrgRE=`
- USDC master deploy tx: `already-active`
- USDC mint tx: `existing-balance`
- USDC withdrawal tx:
  `Us7AaK2DCJI+ISjcMoT4iUhXKFDyfzRiDYDyhGeNjgU=`
- USDC collection tx:
  `284BbYeWklER9X5xFbAY57wsyHaykd3Z3aDpCWCHYQg=`

The final live rerun reused already deployed masters and previously detected
mock Jetton balances, avoiding unnecessary repeat mint gas consumption.

## Deposit Results

Final DB evidence after live rerun:

- `deposit_record` TON: 3 CREDITED rows, total `4001000000` nanoTON.
- `deposit_record` USDT: 4 CREDITED rows, total `4000000000` atomic units.
- `deposit_record` USDC: 4 CREDITED rows, total `4000000000` atomic units.
- Duplicate deposit check for `(chain, tx_hash, log_index)`: 0 rows.
- `chain_scan_height`: `ton-account-message-scanner`, `best_height=67008877`,
  `safe_height=67008877`, `status=ACTIVE`.

Scanner behavior:

- Native scanner credits external inbound TON messages only.
- Native scanner ignores platform-originated messages and known Jetton protocol
  opcodes (`transfer_notification`, `internal_transfer`, `excesses`) so Jetton
  refunds/excess messages are not double-counted as native deposits.
- Jetton scanner parses TEP-74 transfer notifications/internal transfers and
  resolves configured `token_config` masters.

## Withdrawal Results

- `withdrawal_order` TON: 1 CONFIRMED.
- `withdrawal_order` USDT: 1 CONFIRMED.
- `withdrawal_order` USDC: 1 CONFIRMED.
- Repeat call with the same order number returned the existing tx hash and did
  not create a duplicate broadcast order.
- Locked balances were settled to zero after confirmation.

## Collection Results

- `collection_record` TON: 1 CONFIRMED.
- `collection_record` USDT: 1 CONFIRMED.
- `collection_record` USDC: 1 CONFIRMED.
- Repeat call with the same collection number returned the existing tx hash and
  did not create a duplicate collection.

## Ledger Reconciliation

Final `ledger_balance` rows:

- TON owner: available `3946000000`, locked `0`, total `3946000000`.
- USDT owner: available `3900000000`, locked `0`, total `3900000000`.
- USDC owner: available `3900000000`, locked `0`, total `3900000000`.
- Negative ledger rows: `0`.

## Transaction Table

Final `ton_transaction` summary:

- TON: 5 CONFIRMED rows.
- USDT: 6 CONFIRMED rows.
- USDC: 6 CONFIRMED rows.
- Duplicate transaction check for `(chain, tx_hash, asset_symbol, to_address,
  amount)`: 0 rows.

`ton_transaction` confirmation status is synchronized when withdrawal or
collection confirmation succeeds.

## Fee / Dust / UTXO

- TON is not UTXO; UTXO lock/spent states are not applicable.
- Native withdrawal default fee: `5_000_000` nanoTON from `chain_profile`.
- Jetton transfer gas envelope: `70_000_000` nanoTON.
- Jetton forward amount: `10_000_000` nanoTON.
- Dust threshold is not used as a UTXO dust rule for TON; minimum transfer values
  remain database-driven through `chain_asset` / `token_config`.

## Recovery / Idempotency

Verified:

- Repeated scanner run did not change credited ledger balance.
- Repeated withdrawal with the same order number returned the existing tx hash.
- Repeated collection with the same collection number returned the existing tx
  hash.
- `account_sequence` reservation is monotonic and isolates WalletV4R2 seqno
  recovery.
- Failed Jetton collection during an intermediate rerun was marked `FAILED` and
  did not leave locked ledger. The RPC client was then hardened with response
  body diagnostics and retry for TonCenter 429/5xx responses.

## Multi-User

- Deterministic multi-index address derivation is covered by
  `TonAddressGenerationTest`.
- Live test covered separate owner, external recipient, and hot-wallet roles.
- Service restart address stability is covered by deterministic derivation and
  persisted `chain_address` rows.

## Implemented Production Files

Added:

- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/TonTransactionRecord.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonKeyService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonAddressService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonCenterClient.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonApiClient.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonTransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonDepositScanner.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ton/TonChainAdapter.java`

Modified:

- `backendservices/pom.xml`
- `backendservices/wallet-parent/wallet-service/pom.xml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/FeeRateUpdater.java`
- `multi-chain-wallet-schema.sql`
- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `regression-report.md`

Deleted:

- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/future/TonChainAdapter.java`

## Tests Added

- `TonAddressGenerationTest`
- `TonDatabaseFlowIntegrationTest`
- `TonMessageEncodingTest`
- `TonTestnetConnectivityIntegrationTest`
- `TonLiveMockJettonFlowIntegrationTest`

## Database Schema And MBG

Added/reused:

- `ton_transaction`, unique `(chain, tx_hash)`.
- `account_sequence`, unique `(chain, address)`.
- `chain_profile` rows for TON testnet/mainnet.
- `chain_asset` row for TON native.
- `token_config` rows for TON mock USDT/USDC Jetton masters during testnet live
  validation.
- Unified `chain_address`, `deposit_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, and `chain_scan_height`.

No MBG generation was used. No service, scanner, signing, fee, or sequence logic
was generated.

## Test Commands

Passed:

- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.ton.*Test,com.surprising.wallet.service.chain.ton.TonLiveMockJettonFlowIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
- `ATOMEX_MASTER_SEED=<external-secret> mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.ton.TonLiveMockJettonFlowIntegrationTest' -Dton.live.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`
- `mvn -q clean install -DskipTests=false`
- PostgreSQL `select 1`
- Redis `PING`
- wallet-server `--spring.profiles.active=test`, `/actuator/health` returned
  `UP`
- wallet-sig1 `--spring.profiles.active=test`, started and listened on `8004`
- wallet-sig2 `--spring.profiles.active=test`, started in non-web mode and
  second-sign job was active

Full Maven result: passed across the workspace. Startup warnings from public BTC
RPC TLS handshakes did not prevent wallet-server health from returning `UP`.

## Blocked Items

- None for TON commit gate.
- For future TON live reruns, the owner address currently has about `0.826`
  testnet TON remaining. Apply more testnet TON to
  `0QAZqo0OKuwLmIsJT57odv_onrMViV4mnymFhoYst7EUiGWs` before extended live
  testing.

## Risks

- Public TON testnet APIs are not reliable enough for production scanner uptime;
  production should use an authenticated provider or owned indexer abstraction.
- Mock USDT/USDC Jettons are test-only assets. Production USDT/USDC must use
  audited, configured Jetton master addresses.
- TON transaction finality and message indexing differ from EVM logs; production
  should keep the current account/message scanner abstraction separate from EVM.

## Commit / Push

- Commit message: `feat: add ton native jetton wallet flow`.
- Commit hash: created after this report; see final task output.
- Push: no.
