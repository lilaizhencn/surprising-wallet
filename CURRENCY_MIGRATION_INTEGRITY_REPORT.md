# Currency Migration Integrity Report

Generated: 2026-06-24 Asia/Shanghai.

## 1. Conclusion

Result: **runtime UTXO migration and legacy UTXO persistence cleanup completed and verified locally**.

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

Legacy `*_utxo_transaction` tables were deleted locally on
2026-06-24 after a fresh backup and guarded preflight.

Physical deletion conclusion: **old BTC-like UTXO tables have been dropped in
the local validation DB**.

The legacy MBG mapper/service/XML for UTXO persistence has been removed, Java/XML
runtime references to `UtxoTransactionService`, `UtxoTransactionRepository`,
`UtxoTransactionExample`, and `UtxoTransactionMapper` are gone, and one real
DOGE/BCH local-regtest deposit/withdraw/collection cycle has passed against
`utxo_record`. The checked-in `scripts/drop-legacy-bitcoinlike-utxo-tables.sql`
remains for other environments; execute it only after a DB backup/PITR point.

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
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/BitcoinLikeRegtestFullFlowIntegrationTest.java`
- `scripts/migrate-bitcoinlike-utxo-record-backfill.sql`
- `scripts/drop-legacy-bitcoinlike-utxo-tables.sql`
- `CURRENCY_MIGRATION_INTEGRITY_REPORT.md`

Deleted files:

- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/criteria/UtxoTransactionExample.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/UtxoTransactionRepository.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/service/UtxoTransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/service/impl/UtxoTransactionServiceImpl.java`
- `backendservices/wallet-parent/wallet-service/src/main/resources/mapper/UtxoTransactionMapper.xml`

DB initialization updates:

- `multi-chain-wallet-schema.sql` now includes BTC `chain_profile` and
  `chain_asset`, so unified BTC UTXO runtime can read `runtime_currency_id=1`
  from DB instead of deriving it from `CurrencyEnum`.
- `surprising-wallet-init-pgsql.sql` includes the same BTC DB asset/profile
  initialization for clean environments.
- `surprising-wallet-init-pgsql.sql` and `multi-chain-wallet-schema.sql` no
  longer create legacy `*_utxo_transaction` tables.

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

### Runtime Currency Id

`ChainJdbcRepository.listSpendableUtxos`,
`listAvailableUtxosBelowConfirmations`, and `listUtxosByAddress` now map
`UtxoTransaction.currency` from `chain_profile.runtime_currency_id`. They no
longer use `CurrencyEnum.parseName(chain)` for unified UTXO runtime rows.

### Address Registry

BTC/LTC/DOGE/BCH new address generation now writes `chain_address`. Scanner and
withdraw UTXO metadata lookup prefer `chain_address`; legacy `*_address` tables
are read only as historical compatibility/backfill sources. The first validated
new-chain-address cutover was DOGE user `99042401`, address
`2N4VqvAwgzRYrrFKUoDkjXxLE8YB99TrFKi`, inserted into `chain_address` with zero
new rows in `doge_address`.

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
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -DskipTests=false \
  -Dbitcoinlike.regtest.enabled=true \
  -Dtest=BitcoinLikeRegtestFullFlowIntegrationTest test
mvn -q clean install -DskipTests=false
```

Results:

- Target migration DB test: **passed**.
- DOGE/BCH real local-regtest deposit/withdraw/collection test: **passed**.
- Full Maven clean install: **passed**.
- wallet-server `--spring.profiles.active=test`: started.
- wallet-server `/actuator/health`: `{"groups":["liveness","readiness"],"status":"UP"}`.
- wallet-sig1 `--spring.profiles.active=test`: started with an ephemeral
  environment-only test extended key.
- wallet-sig2 `--spring.profiles.active=test`: process started with an
  ephemeral environment-only test extended key.
- DOGE address API after address-route migration: generated
  `2N4VqvAwgzRYrrFKUoDkjXxLE8YB99TrFKi`; `chain_address` increased by one and
  `doge_address` remained unchanged for user `99042401`.

No private key, RPC key, or test funding key was written to source/YAML.

### DOGE/BCH Regtest Evidence

DOGE local regtest:

- deposit txid:
  `34ac7f568667775f06a179c9e0b9f9ce6dd02bd0de3ac3c95ed9b0c3db543a68`
- withdraw txid:
  `e7723d5c79a8695727b1df1c52a2159ed5847278d0ff849cace8b79589ef976e`
- collection txid:
  `35d53c425773a97776f6c193b196c40604dd175106168d18314fd40b87180802`
- deposit address: `n3CGohzAycpjHaQqszBDamAzxH1r5KDSfi`
- withdraw address: `n4hu8Ziwkkr1AeWXSgsEYGj2FJLTvyhUgc`
- hot address: `mrWGppYoaG963tqtabihbYzxn6Q21S5vhD`
- ledger after rollback-scoped validation: `114.00000000 DOGE`
- verified: `deposit_record`, `utxo_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, duplicate deposit idempotency,
  duplicate order/collection claim idempotency, UTXO lock/spend guards,
  non-negative ledger, scanner checkpoint monotonicity.

BCH local regtest:

- deposit txid:
  `ce034235e4907182f1ccf3f20e4b4a69c3baf13a097b3b7d4f337705bb5f3c3a`
- withdraw txid:
  `07b2267f08727b0b6bbb61f14f7d2735529d1ff76c126e83dd4b98800d85e976`
- collection txid:
  `cc9a9b115a8873b728023e33a3a062a9e511349422e6f4600bf88e878f3892c0`
- deposit address:
  `bchreg:qzxtcvjt4nn6gmhme2emk7qtdxrwawrdxgfgeqr2nu`
- withdraw address:
  `bchreg:qzklqdhmu0cqlwaws8mv76gw5c03sjf9fyckf7jzq6`
- hot address:
  `bchreg:qz73fsusqk95lcjwtqxqmjhjs0kl2fzh9cg7yr78hj`
- ledger after rollback-scoped validation: `1.14999000 BCH`
- verified: `deposit_record`, `utxo_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, duplicate deposit idempotency,
  duplicate order/collection claim idempotency, UTXO lock/spend guards,
  non-negative ledger, scanner checkpoint monotonicity.

Note: this regtest validates real local-chain transactions and the unified DB
runtime. It does not persist DB rows because the test rolls DB writes back. It
does not expose or write private keys.

## 7. Risks and Follow-Up

1. Old UTXO tables have been dropped locally. Other environments must execute
   the guarded drop script after backup; the source SQL no longer recreates
   those tables.
2. wallet-server startup hit transient BTC public RPC SSL/connection-reset errors
   during scheduled scan. The service health remained `UP`; production should
   use a stable BTC RPC endpoint.
3. sig1/sig2 test startup requires `ATOMEX_SIG1_MASTER_KEY` /
   `ATOMEX_SIG2_MASTER_KEY`. For local startup verification, ephemeral
   environment-only keys were used. Production/testnet signing must use the real
   secure key injection path.

## 8. Old Table Deletion Decision

Current answer: **done in the local validation DB**.

The local validation DB no longer contains:

- `btc_utxo_transaction`
- `ltc_utxo_transaction`
- `doge_utxo_transaction`
- `bch_utxo_transaction`

Legacy address, withdraw_record, and withdraw_transaction tables were not
dropped. Address runtime migration has started by switching new BTC-like address
generation to `chain_address`; withdraw/signing table routing remains the next
compatibility layer to retire.

For other environments, execute the drop only through:

```bash
psql "$DATABASE_URL" -f scripts/drop-legacy-bitcoinlike-utxo-tables.sql
```

The script checks every remaining spendable legacy UTXO exists as AVAILABLE in
`utxo_record`; if any row is missing, it raises an exception and rolls back.

After that migration, `utxo_record` is the only BTC-like UTXO runtime table.
