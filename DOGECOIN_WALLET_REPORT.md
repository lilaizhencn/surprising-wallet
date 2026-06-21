# Dogecoin Wallet Report

Generated: 2026-06-21 23:07 Asia/Shanghai.

## 1. Overall Conclusion

- Dogecoin wallet flow is implemented on bitcoinj `0.17.1` without another UTXO SDK.
- DOGE uses independent legacy P2SH 2-of-3 addresses/signing, Dogecoin network parameters, fee policy, dust policy, runtime profile, tables, scanner, withdrawal, collection, recovery, unified UTXO state, and ledger settlement.
- Real Dogecoin testnet RPC read/scan validation passed.
- Live funded deposit/withdraw/collection is deferred because no DOGE testnet funds were available, as permitted by the updated task instruction.
- BTC, LTC, EVM, and TRON full Maven regression passed.
- Push: no.

## 2. Modified Files

- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/deposit/AbstractScanBlockJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/AbstractBatchWithdrawJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/LtcSigningRecoveryJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/config/PubKeyConfig.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/service/TransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/AbstractBtcLikeWallet.java`
- `backendservices/wallet-sig1/src/main/java/com/surprising/wallet/sig/first/config/PubKeyConfig.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/ChainType.java`
- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/currency/CurrencyEnum.java`
- `multi-chain-wallet-schema.sql`
- `surprising-wallet-init-pgsql.sql`
- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `regression-report.md`

## 3. New Files

- DOGE wallet, RPC command, scanner, withdrawal, collection, recovery, first/second signer classes.
- Dogecoin network, fee, legacy multisig address, transaction builder, and P2SH fee calculator classes.
- Dogecoin address, signing, fee/network, and PostgreSQL flow tests.

## 4. Deleted Files

- `LitecoinSettlementService.java` was replaced by the generalized `BitcoinLikeSettlementService.java`; LTC behavior is preserved.

## 5. Database Schema

- Added DOGE testnet/mainnet `chain_profile`.
- Added DOGE `chain_asset`.
- Added legacy compatibility tables: `doge_address`, `doge_utxo_transaction`, `doge_withdraw_record`, `doge_withdraw_transaction`.
- Reused unified `utxo_record`, `deposit_record`, `withdrawal_order`, `collection_record`, `ledger_balance`, `chain_scan_height`, and `hot_wallet_address`.
- Unique UTXO key remains `(chain, tx_hash, vout)`.
- Incremental migration and clean-database initialization both passed.

## 6. MBG

- No MBG run was needed. No generated service/scanner/signing logic was introduced.

## 7. Currency Compatibility

- `CurrencyEnum.DOGE` exists only for legacy table/queue/signer dispatch.
- Runtime configuration comes from `chain_profile`.
- `CurrencyIds` was not modified or used for DOGE.

## 8. Runtime Currency ID

- DOGE runtime currency id: `41`.
- Pre-migration checks found no id `41` in `chain_profile`, `user_asset`, or `currency_balance`.

## 9. BIP44

- DOGE BIP44 coin type: `3`.
- Runtime id `41` is never used as the derivation coin type.

## 10. Network Parameters

- Mainnet P2PKH/P2SH/WIF: `30 / 22 / 158`; port `22556`; magic `0xc0c0c0c0`.
- Testnet P2PKH/P2SH/WIF: `113 / 196 / 241`; port `44556`; magic `0xfcc1b7dc`.
- Dogecoin SegWit is disabled; DOGE uses legacy P2SH, not BTC/LTC P2WSH.
- Testnet RPC: `https://dogecoin-testnet.gateway.tatum.io`.

## 11. Address Generation

- Deposit: `2MtNHNEL8YcV3EWM3DZhFcZhxCwNCKSsrtk`, path `m/44/3/1/9101/0`.
- Collection source: `2NCe91VjbxN6qNtuGNgsTrzwBZzo9RZfSDs`, path `m/44/3/1/9102/0`.
- Hot: `2NDPypMNMLaAvi1Lf9UEETD8iwwLWi6SbHv`, path `m/44/3/0/0/0`.
- All are deterministic Dogecoin testnet P2SH 2-of-3 addresses and are persisted in `doge_address`.

## 12-14. Live Transaction IDs

- Deposit txid: deferred pending testnet funds.
- Withdraw txid: deferred pending funded deposit.
- Collection txid: deferred pending funded deposit/withdrawal.

## 15. UTXO State

- PostgreSQL-backed synthetic flow verified one insert, one lock winner, duplicate lock rejection, and guarded release.
- Real scanner block contained no platform output; DOGE unified/legacy UTXO rows remained zero as expected.

## 16. Fee / Dust

- Recommended fee: `0.01 DOGE/kB` = `1000 koinu/byte`.
- Recommended dust threshold: `0.01 DOGE` = `1,000,000 koinu`.
- Hard lower dust reference: `0.001 DOGE`.
- P2SH 2-of-3 serialized-size estimator and two-stage signed transaction tests passed.

## 17-19. Records

- `deposit_record`: real non-platform scan created zero rows; synthetic idempotency test created exactly one row inside a rolled-back transaction.
- `withdrawal_order`: live record deferred.
- `collection_record`: live record deferred.

## 20. Ledger

- Synthetic deposit credited exactly once and produced no negative balance.
- Real DOGE ledger remains zero because no testnet funds were received.

## 21. Idempotency

- Deposit unique key and `credited=false` guard passed against PostgreSQL.
- UTXO duplicate lock guard passed.
- Broadcast path derives the txid before sending and checks whether it already exists on-chain.
- Deterministic collection id prevents duplicate collection creation.

## 22. Recovery

- Scanner checkpoint advanced to `65162280`; persisted `chain_scan_height` is `DOGE/doge-block-scanner`.
- DOGE stale signing recovery uses an atomic database claim before Redis requeue.
- Failed signing releases unified/legacy UTXO locks and ledger/user frozen balances.

## 23. Multi-User

- Independent users `9101` and `9102` generated distinct P2SH addresses.
- Funded multi-user flow is deferred until testnet DOGE arrives.

## 24-25. Commands / Results

- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire: 69 tests, 0 failures, 0 errors, 12 skipped.
- DOGE address/database flow tests: passed.
- Dogecoin network/P2SH signing/fee tests: passed.
- Real RPC `getblockcount`, `getblockhash`, `getblock`, and verbose `getrawtransaction`: passed.
- Real scanner processed block `65162280`, tx `7f33306a5e2074ca340d7545ca0bd38fc4ce7a478fdf238130efe938d980cac5`.
- Non-platform address was ignored; checkpoint advanced; no deposit or UTXO was written.
- wallet-server health `UP`; wallet-sig1 and wallet-sig2 started.
- PostgreSQL `select 1` and Redis `PONG` passed.

## 26. Deferred / Blocked

- Funded DOGE live deposit, withdrawal, collection, confirmation settlement, and on-chain ledger reconciliation are deferred until testnet funds are supplied.

## 27. Risks

- Public Tatum RPC is rate-limited and is suitable for validation, not production scanning.
- Dogecoin testnet may mine rapidly and can have unstable public explorer availability.
- Production should use controlled Dogecoin Core infrastructure.

## 28. Commit

- Message: `feat: add dogecoin wallet flow`.
- Hash: this report is part of the commit; resolve after commit.

## 29. Push

- No.

## Testnet Funding Request

Send at least `50 DOGE` testnet to:

`2MtNHNEL8YcV3EWM3DZhFcZhxCwNCKSsrtk`

This provides enough margin for deposit, withdrawal, collection, and Dogecoin's fee/dust policy in the next live-gate task.
