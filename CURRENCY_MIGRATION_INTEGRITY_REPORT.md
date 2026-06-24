# Currency Migration Integrity Report

Generated: 2026-06-24 Asia/Shanghai.

## 1. Conclusion

Result: **runtime UTXO migration completed and verified locally**.

BTC/LTC/DOGE/BCH available UTXOs have been backfilled into `utxo_record`, and
BTC-like runtime paths now use `utxo_record` for:

- scanner UTXO persistence;
- wallet balance calculation;
- withdrawal UTXO selection;
- withdrawal UTXO locking/release;
- broadcast idempotency without legacy UTXO rewrites;
- confirmed withdraw/collection spend settlement;
- collection UTXO selection and locking;
- address transaction query API.

Legacy `*_utxo_transaction` tables were **not deleted**. They are now retained
as historical compatibility data only.

Production deletion conclusion: **do not delete the old UTXO tables yet**.

They can be considered for deletion only after a separate cleanup change removes
or archives the legacy MBG mapper/service classes and after one full live
deposit/withdraw/collection soak cycle confirms no fallback path queries those
tables. For now, freeze them as read-only historical tables.

Push status: **NO**.

## 2. DB Backfill

Backup created before mutation:

- `db-backups/utxo-runtime-migration-20260624.sql`

Reusable idempotent script added:

- `scripts/migrate-bitcoinlike-utxo-record-backfill.sql`

Backfill executed:

| Chain | Affected Rows | Affected Amount |
|---|---:|---:|
| BCH | 3 | 15.99999280 |
| BTC | 3 | 0.00327467 |
| DOGE | 3 | 1599.98937000 |
| LTC | 2 | 0.00999278 |

Post-backfill available UTXO reconciliation:

| Chain | Legacy Available Rows | Legacy Available | Unified Rows | Unified Available | Delta |
|---|---:|---:|---:|---:|---:|
| BCH | 3 | 15.99999280 | 3 | 15.99999280 | 0 |
| BTC | 3 | 0.00327467 | 3 | 0.00327467 | 0 |
| DOGE | 3 | 1599.98937000 | 3 | 1599.98937000 | 0 |
| LTC | 2 | 0.00999278 | 2 | 0.00999278 | 0 |

`utxo_record` state after migration:

| Chain | State | Rows | Amount |
|---|---|---:|---:|
| BCH | AVAILABLE | 3 | 15.99999280 |
| BCH | SPENT | 2 | 15.00000000 |
| BTC | AVAILABLE | 3 | 0.00327467 |
| DOGE | AVAILABLE | 3 | 1599.98937000 |
| DOGE | SPENT | 3 | 1700.00000000 |
| LTC | AVAILABLE | 2 | 0.00999278 |
| LTC | SPENT | 2 | 0.01500000 |

## 3. Code Changes

Modified files:

- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/wallet/AbstractBtcLikeWallet.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/service/TransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/BitcoinLikeSettlementService.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/deposit/AbstractScanBlockJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/AbstractBatchWithdrawJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/withdraw/RbfBumpJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/BtcCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/LtcCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/DogeCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/transfer/BchCollectionJob.java`
- `backendservices/wallet-parent/wallet-server/src/main/java/com/surprising/wallet/jobs/WalletController.java`

Added files:

- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/BitcoinLikeUnifiedUtxoRuntimeMigrationTest.java`
- `scripts/migrate-bitcoinlike-utxo-record-backfill.sql`
- `CURRENCY_MIGRATION_INTEGRITY_REPORT.md`

## 4. Runtime Behavior After Migration

### Scanner

`AbstractBtcLikeWallet` now persists BTC/LTC/DOGE/BCH scanned UTXOs to
`utxo_record` only. It no longer inserts BTC-like scan results into legacy
`*_utxo_transaction` tables.

Confirmation updates use `utxo_record`. To prevent duplicate credit on
pre-migration backfilled rows, confirmation refresh only advances existing
`deposit_record` rows. Backfilled UTXOs that have no `deposit_record` are not
silently credited as new deposits.

### Withdrawal

`AbstractBatchWithdrawJob` now selects BTC/LTC/DOGE/BCH spendable UTXOs from
`utxo_record` and locks them via `ChainJdbcRepository.lockUtxo`.

For BTC-like currencies it no longer updates legacy UTXO rows to SIGNING.

### Broadcast and Settlement

`AbstractBtcLikeWallet.sendRawTransaction` no longer rewrites legacy UTXO
`spent_tx_id` for BTC-like currencies.

`BitcoinLikeSettlementService` now supports BTC in addition to LTC/DOGE/BCH and
settles confirmed spends by marking `utxo_record` rows `SPENT`.

### Collection

BTC/LTC/DOGE/BCH collection jobs now select from `utxo_record`, create/claim
`collection_record`, lock inputs in `utxo_record`, and do not update legacy
UTXO tables.

### RBF

BTC RBF no longer edits legacy UTXO rows. It keeps the same input set under the
unified `utxo_record` lock reference.

### Query API

`/wallet/v1/addresses/transactions` reads BTC/LTC/DOGE/BCH rows from
`utxo_record`.

## 5. Integrity Checks

Executed checks:

- Duplicate `(chain, tx_hash, vout)` in `utxo_record`: `0`.
- Negative `ledger_balance`: `0`.
- Available UTXO amount delta between legacy and unified tables: `0` for
  BTC/LTC/DOGE/BCH.
- PostgreSQL `select current_database(), current_user, now()`: passed.
- Redis `PING`: `PONG`.

## 6. Tests

Commands executed:

```bash
mvn -q -DskipTests compile
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -DskipTests=false \
  -Dutxo.migration.db.enabled=true \
  -Dtest=BitcoinLikeUnifiedUtxoRuntimeMigrationTest test
mvn -q clean install -DskipTests=false
```

Results:

- Target migration DB test: **passed**.
- Full Maven clean install: **passed**.
- wallet-server `--spring.profiles.active=test`: started.
- wallet-server `/actuator/health`: `{"groups":["liveness","readiness"],"status":"UP"}`.
- wallet-sig1 `--spring.profiles.active=test`: started with an ephemeral
  environment-only test extended key.
- wallet-sig2 `--spring.profiles.active=test`: process started with an
  ephemeral environment-only test extended key.

No private key, RPC key, or test funding key was written to source/YAML.

## 7. Risks and Follow-Up

1. The old UTXO tables still exist and still have MBG mapper/service classes.
   They should not be dropped until a cleanup commit removes those compile-time
   dependencies or moves the tables to an archive schema.
2. wallet-server startup hit transient BTC public RPC SSL/connection-reset errors
   during scheduled scan. The service health remained `UP`; production should
   use a stable BTC RPC endpoint.
3. sig1/sig2 test startup requires `ATOMEX_SIG1_MASTER_KEY` /
   `ATOMEX_SIG2_MASTER_KEY`. For local startup verification, ephemeral
   environment-only keys were used. Production/testnet signing must use the real
   secure key injection path.

## 8. Old Table Deletion Decision

Current answer: **not safe to delete old tables today**.

Safe next sequence:

1. Keep `btc_utxo_transaction`, `ltc_utxo_transaction`,
   `doge_utxo_transaction`, and `bch_utxo_transaction` read-only.
2. Run one full live or regtest cycle per BTC-like chain after this migration:
   deposit, scanner, withdraw, collection, recovery, idempotency.
3. Remove legacy UTXO mapper/service usage from code, tests, and API fallback.
4. Archive old table data.
5. Drop old tables in a dedicated DB migration.

Until step 5, `utxo_record` is the runtime source of truth; old tables are
historical compatibility only.
