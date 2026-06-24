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

2026-06-24 no-fallback follow-up: BTC/LTC/DOGE/BCH address runtime now reads
`chain_address` only; the previous legacy `*_address` fallback/backfill path was
removed from `AddressServiceImpl` and BTC-like address listing/hot-info APIs.
BTC-like signing runtime now writes, recovers, broadcasts, and confirms through
`chain_signing_transaction` instead of legacy `*_withdraw_transaction` tables.
Legacy terminal signing history was copied into `chain_signing_transaction` for
audit/live-test consistency; active legacy signing rows are intentionally
rejected by `scripts/migrate-bitcoinlike-signing-transaction-cutover.sql` to
avoid duplicate broadcast risk.

After the post-cutover regtest/live gates passed, the local validation DB also
dropped the legacy BTC-like `*_withdraw_transaction` tables through
`scripts/drop-legacy-bitcoinlike-withdraw-transaction-tables.sql`. Clean schema
initialization no longer creates those tables.

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
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/ltc/LitecoinLiveFlowIntegrationTest.java`

Added files:

- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/BitcoinLikeUnifiedUtxoRuntimeMigrationTest.java`
- `backendservices/wallet-parent/wallet-service/src/test/java/com/surprising/wallet/service/chain/BitcoinLikeRegtestFullFlowIntegrationTest.java`
- `scripts/migrate-bitcoinlike-utxo-record-backfill.sql`
- `scripts/drop-legacy-bitcoinlike-utxo-tables.sql`
- `scripts/migrate-bitcoinlike-signing-transaction-cutover.sql`
- `scripts/drop-legacy-bitcoinlike-withdraw-transaction-tables.sql`
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
- `surprising-wallet-init-pgsql.sql` and `multi-chain-wallet-schema.sql` now
  create `chain_signing_transaction` with unique
  `(chain, business_type, business_no)` plus `(chain, tx_id)` and
  `(chain, status, update_date)` indexes.
- `surprising-wallet-init-pgsql.sql` and `multi-chain-wallet-schema.sql` no
  longer create BTC/LTC/DOGE/BCH `*_withdraw_transaction` tables.

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

BTC/LTC/DOGE/BCH new address generation now writes `chain_address`. Scanner,
withdraw UTXO metadata lookup, BTC-like hot-info, and BTC-like address listing
read `chain_address` only; there is no legacy `*_address` runtime fallback. The
first validated new-chain-address cutover was DOGE user `99042401`, address
`2N4VqvAwgzRYrrFKUoDkjXxLE8YB99TrFKi`, inserted into `chain_address` with zero
new rows in `doge_address`.

### Signing Transaction Runtime

`chain_signing_transaction` is the BTC-like signing source of truth. The
Redis payload remains the existing `WithdrawTransaction` DTO to preserve sig1
and sig2 signing algorithms, but BTC/LTC/DOGE/BCH no longer insert, update,
recover, or confirm through `btc/ltc/doge/bch_withdraw_transaction`.

Verified cutover behavior:

- withdrawal build creates one `chain_signing_transaction` row and uses its id
  as `utxo_record.lock_ref`;
- collection build creates one deterministic `COLLECTION` row and uses its id
  as `utxo_record.lock_ref`;
- broadcast idempotency checks `chain_signing_transaction.status/tx_id`;
- scanner confirmation lookup resolves sent txids from `chain_signing_transaction`;
- stale-signing recovery requeues rows from `chain_signing_transaction`;
- RBF reads and updates `chain_signing_transaction`, while `withdraw_record`
  remains only the external/business compatibility record.

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
mvn -q -pl backendservices/wallet-parent/wallet-service -am \
  -DskipTests=false \
  -Dtest=LitecoinLiveFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dltc.live.enabled=true \
  -Dltc.live.withdraw.txid=ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c \
  -Dltc.live.collection.txid=34c2a03b9696b558c794350039d19ff38f76a44b1a3717f3531be73f31274949 test
mvn -q clean install -DskipTests=false
```

Results:

- Target migration DB test: **passed**.
- DOGE/BCH real local-regtest deposit/withdraw/collection test: **passed**.
- LTC real live testnet deposit/withdraw/collection gate: **passed** after
  forcing the test HTTP client to HTTP/1.1 for litecoinspace TLS compatibility.
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
  `4a351af4b94b7398a675818fc3bfe234f6e5f95644959c33754fde75ee7b3e49`
- withdraw txid:
  `6ff8854531fc9a1b3b96e7011545ac6edc37e4a86e8385169f255c80a3306e4c`
- collection txid:
  `47e88653105ff8a21162cbc5a01556bd378ea541ebd8dfb58ab5b8e39a3d70b9`
- deposit address: `miUsT5KNY57qmM3wGfb2S7ykzfmAXZPkXx`
- withdraw address: `muferxGf4kJbyRMXuocUA4oQpXdGrw4WfP`
- hot address: `mu9LwGVQ9D45KX57nmj8up2XRU4iaqwTod`
- ledger after rollback-scoped validation: `114.00000000 DOGE`
- verified: `deposit_record`, `utxo_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, duplicate deposit idempotency,
  duplicate order/collection claim idempotency, UTXO lock/spend guards,
  non-negative ledger, scanner checkpoint monotonicity.

BCH local regtest:

- deposit txid:
  `1b58a16b7482cb5b4c5d369012260b2702e36f6c5f8ae25efd6fb149230d0287`
- withdraw txid:
  `59c6c4deff09bfc5cb58bc309cc7f79f876215df779c00641ae1bd5ca8aff0ee`
- collection txid:
  `1b002c88cd57717c03f2f6fa154246feaed87d906987b5e88621b44728f794b5`
- deposit address:
  `bchreg:qqsajn05xgjnlgfc3q2fxrg435yztwyhmsxh0qdn4e`
- withdraw address:
  `bchreg:qruknj6smxk5n4vxhst4lns6eyx4tslzcymac530lj`
- hot address:
  `bchreg:qry6uk3k9lc2p2krqwcddt6l4pqgdqvejy9epx46cd`
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
2. Old BTC-like withdraw transaction tables have been dropped locally. Other
   environments must execute
   `scripts/drop-legacy-bitcoinlike-withdraw-transaction-tables.sql` only after
   the signing cutover migration and backup/PITR point.
3. wallet-server startup hit transient BTC public RPC SSL/connection-reset errors
   during scheduled scan when using the default public endpoint. The final
   startup check disabled scanning to avoid external RPC noise, and health
   remained `UP`; production should use a stable BTC RPC endpoint.
4. sig1/sig2 test startup requires `ATOMEX_SIG1_MASTER_KEY` /
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
- `btc_withdraw_transaction`
- `ltc_withdraw_transaction`
- `doge_withdraw_transaction`
- `bch_withdraw_transaction`

Legacy address and withdraw_record tables were not dropped because non-BTC-like
and external business compatibility paths still use them. BTC-like runtime no
longer uses legacy address fallback or legacy `*_withdraw_transaction` signing
tables. The BTC-like `*_withdraw_transaction` table family has been physically
dropped in the local validation DB after guarded migration and post-drop
regtest/live validation.

For other environments, execute the drop only through:

```bash
psql "$DATABASE_URL" -f scripts/drop-legacy-bitcoinlike-utxo-tables.sql
```

The script checks every remaining spendable legacy UTXO exists as AVAILABLE in
`utxo_record`; if any row is missing, it raises an exception and rolls back.

After that migration, `utxo_record` is the only BTC-like UTXO runtime table.
