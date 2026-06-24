# Litecoin Wallet Report

Generated: 2026-06-21 18:50 Asia/Shanghai.

## 1. Overall Conclusion

- Litecoin testnet live gate passed using real funds and LitecoinSpace testnet Esplora.
- Deposit, withdrawal, collection, confirmation settlement, idempotency, UTXO locking/release, scanner recovery, and ledger reconciliation passed.
- Full Maven regression passed: 64 tests, 0 failures, 0 errors, 11 skipped. The separately enabled live gate passed 4 tests with 0 skipped.
- PostgreSQL, Redis, wallet-server health, wallet-sig1 startup, and wallet-sig2 startup passed.
- No DOGE or BCH implementation was started before this LTC gate.
- Push: no.

## 2. Modified Files

- `README.md`
- `README_CN.md`
- `LITECOIN_WALLET_REPORT.md`
- `regression-report.md`
- `backendservices/common/src/main/java/com/surprising/common/config/CurrencyIds.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/deposit/AbstractScanBlockJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/LtcCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/AbstractBatchWithdrawJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/BatchLtcWithdrawJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/SendRawTxJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/service/TransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/AbstractBtcLikeWallet.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/impl/LtcWallet.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/ltc/LitecoinLiveFlowIntegrationTest.java`
- `backendservices/wallet-sig1/src/main/java/com/surprising/wallet/sig/first/config/PubKeyConfig.java`
- `backendservices/wallet-sig1/src/main/resources/application-test.yaml`
- `backendservices/wallet-sig1/src/main/resources/application.yaml`
- `backendservices/wallet-sig1/src/test/java/com/surprising/wallet/sig/first/test/FullSigningTest.java`
- `backendservices/wallet-sig2/src/main/resources/application-test.yaml`
- `backendservices/wallet-sig2/src/main/resources/application.yaml`
- `currency-sdks/bitcoin-sdk/src/test/java/sdk/core/KeyGeneratorTest.java`
- `currency-sdks/wallet-client/src/main/java/com/surprising/wallet/client/RpcCommandProcessor.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/currency/CurrencyEnum.java`
- `multi-chain-wallet-schema.sql`
- `surprising-wallet-init-pgsql.sql`

## 3. New Files

- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/LtcSigningRecoveryJob.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ltc/LitecoinEsploraCommand.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/ltc/LitecoinSettlementService.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/BitcoinLikeChainProfile.java`

## 4. Deleted Files

- None.

## 5. Database Schema Changes

- Added `chain_profile`; LTC testnet/mainnet rows use runtime currency id `24` and BIP44 coin type `2`.
- Added `utxo_record` with unique `(chain, tx_hash, vout)`.
- Changed `withdrawal_order` uniqueness to `(chain, order_no)`.
- Changed `collection_record` uniqueness to `(chain, collection_no)`.
- Reused `chain_asset`, `chain_scan_height`, `hot_wallet_address`, `deposit_record`, `withdrawal_order`, `collection_record`, and `ledger_balance`.
- The migration was applied to the local PostgreSQL `wallet` database successfully.
- Fixed deterministic seed identities for BTC/LTC height, balance, and multisig rows so the full initialization script works on an empty database.

## 6. MBG Generation

- No MyBatis Generator run was needed.
- No service, scanner, wallet, signer, or fee logic was generated.
- Unified tables are accessed through the hand-written `ChainJdbcRepository`.

## 7. CurrencyEnum / CurrencyIds Compatibility

- `CurrencyEnum` remains a legacy adapter for wallet lookup, table sharding, Redis queues, jobs, and signer dispatch.
- `CurrencyIds` remains legacy-only and is not used by the LTC flow.
- LTC runtime currency id is loaded and validated from `chain_profile`.
- New-chain source of truth is the database/application configuration, not either legacy constant class.
- Full dependency and collision analysis is in `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`.

## 8. Runtime Currency ID

- LTC runtime currency id: `24`.
- It is distinct from BIP44 coin type and must not be resolved through legacy `CurrencyIds.LTC`.

## 9. BIP44 Coin Type

- LTC BIP44 coin type: `2`.
- Address and signer derivation use coin type `2`, while legacy routing uses runtime id `24`.

## 10. Network Parameters

- Network: Litecoin testnet.
- P2PKH prefix: `111`.
- P2SH prefix: `58`.
- Bech32 HRP: `tltc`.
- Default fee rate: `2` litoshi/vbyte.
- Dust threshold: `1000` litoshi.
- Withdrawal confirmations: `6`.
- Explorer/API: `https://litecoinspace.org/testnet/`.
- The adapter uses Litecoin-specific Esplora block/transaction/broadcast APIs; it does not use BTC network parameters.

## 11. Address Generation

- Deposit address: `tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj`.
- Controlled withdrawal/collection address: `tltc1qydpzhcujqtca9uuepts0k996jfv483xlnkf8majw0f0umaht9j6q2aktvc`.
- Hot address: `tltc1qku2kf64evgw0m79sypm3tp39js97d2e6j6xl6ntf089nvzpkxvnsnc54wn`.
- All are Litecoin testnet 2-of-3 P2WSH addresses.

## 12. Deposit Result

- Txid: `24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f`.
- Explorer: `https://litecoinspace.org/testnet/tx/24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f`.
- Block: `4773130`.
- Amount: `0.01000000 tLTC`.
- Confirmations at final evidence capture: `324`.
- Scanner started at block `4773130`, detected the exact address/output, and advanced `chain_scan_height`.
- Final state: `CREDITED`.
- The state model supports `DETECTED -> CONFIRMING -> CONFIRMED -> CREDITED`; this historical deposit already exceeded the confirmation threshold when rescanned, so the persisted final transition completed immediately.

## 13. Withdrawal Result

- Order: `ltc-live-gate-20260621-001`.
- Txid: `ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c`.
- Explorer: `https://litecoinspace.org/testnet/tx/ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c`.
- Block: `4773436`.
- Confirmations at final evidence capture: `18`.
- Destination amount: `0.00500000 tLTC`.
- Destination was the controlled second Litecoin testnet address.
- Final status: `CONFIRMED`.
- Balance freeze, UTXO lock, two signatures, broadcast, confirmation settlement, and spent marking passed.

## 14. Collection Result

- Collection id: `ltc-collection-ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c-0`.
- Txid: `34c2a03b9696b558c794350039d19ff38f76a44b1a3717f3531be73f31274949`.
- Explorer: `https://litecoinspace.org/testnet/tx/34c2a03b9696b558c794350039d19ff38f76a44b1a3717f3531be73f31274949`.
- Block: `4773439`.
- Confirmations at final evidence capture: `15`.
- Hot-wallet output: `0.00499682 tLTC`.
- Final status: `CONFIRMED`.

## 15. UTXO State

- Deposit UTXO `(deposit txid, vout 0)`: `SPENT`, spent by the withdrawal tx.
- Withdrawal destination UTXO `(withdraw txid, vout 0)`: `SPENT`, spent by the collection tx.
- Withdrawal change UTXO `(withdraw txid, vout 1)`: `AVAILABLE`.
- Collection hot-wallet UTXO `(collection txid, vout 0)`: `AVAILABLE`.
- Deposit unique count in unified `utxo_record`: `1`.
- The live test also transactionally verified single-winner lock and guarded release behavior.

## 16. Fee / Dust

- Withdrawal: fee `404` litoshi, weight `803`, vsize `201`, effective rate about `2.01` litoshi/vbyte.
- Withdrawal quoted/user fee: `320` litoshi; the platform absorbed the `84` litoshi difference while preserving correct chain fee and non-negative ledger state.
- Collection: fee `318` litoshi, weight `632`, vsize `158`, effective rate about `2.01` litoshi/vbyte.
- Collection output remained above the `1000`-litoshi dust threshold.
- Fee-rate and dust unit tests passed.

## 17. deposit_record

- Exactly one row exists for `(LTC, deposit txid, vout/log_index 0)`.
- Status: `CREDITED`.
- `credited = true`.
- No deposit rows were created for the platform withdrawal or collection transactions.

## 18. withdrawal_order

- `ltc-live-gate-20260621-001`: `CONFIRMED`.
- Tx hash matches the live withdrawal txid.
- A pre-gate failed attempt remains explicitly marked `FAILED`; it has no tx hash and did not broadcast.

## 19. collection_record

- Exactly one live collection record exists.
- Status: `CONFIRMED`.
- Tx hash matches the live collection txid.
- The migrated terminal signing history contains the live withdrawal and
  collection txids in `chain_signing_transaction`; runtime signing no longer
  uses `ltc_withdraw_transaction`.

## 20. ledger_balance Reconciliation

- Account `9001`, chain/asset `LTC/LTC`:
  - available: `0.004996800000000000`
  - locked: `0`
  - total: `0.004996800000000000`
- Legacy `user_asset`:
  - balance: `0.00499680`
  - frozen: `0`
- Reconciliation formula: `0.01000000 - 0.00500000 - 0.00000320 = 0.00499680`.
- Negative LTC ledger rows: `0`.

## 21. Idempotency

- The same deposit block was scanned repeatedly; deposit, legacy UTXO, unified UTXO, and ledger credit remained single-entry.
- Re-inserting the signed withdrawal into the done queue was skipped using persisted `SENT`/txid state; no second broadcast occurred.
- Re-inserting the signed collection was also skipped; no second collection broadcast occurred.
- Deterministic collection id prevents duplicate collection records.

## 22. Recovery

- Scanner stop/restart resumed from `chain_scan_height` and did not lose or duplicate the deposit.
- Failed build/signing paths release legacy and unified UTXO locks and release frozen ledger/user balances.
- `LtcSigningRecoveryJob` requeues stale `SIGNING` transactions using an atomic claim.
- Broadcast retry first checks the locally derived txid on-chain, preventing duplicate broadcast after uncertain RPC responses.

## 23. Multi-User

- The LTC live gate used account `9001` and two independently derived controlled addresses.
- Address-index isolation passed in `LitecoinAddressGenerationTest`.
- Cross-user live funding was not required for this LTC gate; DOGE/BCH stages must add their explicitly required multi-user live tests.

## 24. Test Commands

- `mvn -q clean install -DskipTests=false`
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am -Dtest=LitecoinLiveFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dltc.live.enabled=true -Dltc.live.withdraw.txid=<txid> -Dltc.live.collection.txid=<txid> test`
- `psql -d wallet -Atc 'select 1'`
- `createdb <temporary>; psql -v ON_ERROR_STOP=1 -d <temporary> -f surprising-wallet-init-pgsql.sql; dropdb <temporary>`
- `redis-cli ping`
- `java -jar .../wallet-server-1.0.0-SNAPSHOT.jar --spring.profiles.active=test`
- `java -jar .../wallet-sig1-1.0.0-SNAPSHOT.jar --spring.profiles.active=test`
- `java -jar .../wallet-sig2-1.0.0-SNAPSHOT.jar --spring.profiles.active=test`

Secrets were supplied through environment variables and were not written to commands, reports, production YAML, or committed files.

## 25. Test Results

- Full Maven: passed.
- Full Surefire summary: 64 tests, 0 failures, 0 errors, 11 skipped.
- Strict live LTC test: 4 tests, 0 failures, 0 errors, 0 skipped.
- BTC SDK regression: passed within full Maven.
- EVM core regression: passed within full Maven.
- TRON core regression: passed within full Maven.
- PostgreSQL: `select 1` passed.
- Incremental migration rerun: passed idempotently.
- Full initialization SQL: passed on a disposable empty database; LTC profiles and chain-scoped uniqueness constraints were verified.
- Redis: `PONG`.
- wallet-server: started; `/actuator/health` returned `UP`.
- wallet-sig1: started successfully.
- wallet-sig2: started successfully.
- Secret scan: no extended private key, WIF private key, plaintext production password, or plaintext production master key found after remediation.
- `application-prod.yaml` files use environment placeholders and do not generate keys.

## 26. Blocked Items

- None for the Litecoin testnet commit gate.

## 27. Risks

- LitecoinSpace Esplora is an external public dependency; production should use a controlled Litecoin Core/Esplora deployment with availability monitoring.
- The legacy and unified models are mirrored during migration; reconciliation monitoring is required until legacy jobs are retired.
- The live withdrawal charged the configured estimate while actual witness size cost `84` extra litoshi; platform fee subsidy is explicit, but a later change should choose whether customer fee quotation must include a worst-case witness margin.
- Mainnet activation was not performed in this testnet gate.

## 28. Commit

- Commit message: `feat: add litecoin wallet flow`.
- Commit hash: this report is part of the commit; resolve with `git rev-parse HEAD` after creation.

## 29. Push

- No.
