# Legacy Cleanup Report

Date: 2026-06-24

## Scope

This cleanup only touches legacy routing/adapters and read/write entry selection.
It does not change scanner parsing, withdrawal construction/broadcast logic,
collection construction, UTXO signing, EVM/TRON/SOL/TON/APTOS/SUI chain logic,
chain keys, signatures, table structures, or database data.

Funding system impact: **NO**

## Required Grep Scan

Commands executed:

```bash
grep -R "CurrencyEnum" .
grep -R "CurrencyIds" .
grep -R "switch.*currency" .
```

Final active-code scan:

```bash
grep -R -l "CurrencyEnum" backendservices currency-sdks --include='*.java'
grep -R -l "CurrencyIds" backendservices currency-sdks --include='*.java'
grep -R -n "switch.*currency" backendservices currency-sdks --include='*.java'
```

Results:

| Item | Result |
| --- | --- |
| `CurrencyEnum` | Still present in legacy adapter, wallet implementation, signer, fixed jobs, and compatibility APIs |
| `CurrencyIds` | Only `backendservices/common/.../CurrencyIds.java` and `SAFETOKEN.java` |
| `switch.*currency` | No matches |

## CurrencyEnum Dependency List

| Area | Files | Usage | Replaceable now |
| --- | --- | --- | --- |
| DB Asset routing adapter | `AssetRoutingService` | Converts DB `chain_profile` routing to legacy enum only at adapter boundary | Kept intentionally |
| Unified service routing | `TransactionService`, `BitcoinLikeSettlementService`, `AddressServiceImpl`, `WalletController`, `AbstractScanBlockJob` | Runtime decisions now call `chain_profile` lookup through `AssetRoutingService` | Replaced |
| Legacy signing transaction DAO | `ChainJdbcRepository` signing methods | Uses enum as compatibility input for `chain_signing_transaction` chain derivation | Yes, future overload can accept `chain` directly |
| Legacy wallet API | `IWallet`, `WalletContext`, `AbstractWallet`, `AddressService`, `WithdrawTransactionService` | Legacy wallet beans and old sharded table APIs still expose enum types | Not safely in this pass |
| BTC-like wallet implementations | `BtcWallet`, `LtcWallet`, `DogeWallet`, `BchWallet`, `AbstractBtcLikeWallet` | Fixed wallet beans and chain-specific address behavior | Not changed per no-chain-logic rule |
| ETH/TRON/ERC20 legacy wallet | `AbstractEthLikeWallet`, `EthWallet`, `Erc20Wallet`, `TronWallet`, `AbstractAccountWallet` | Old account-model wallet path | Marked as remaining legacy runtime surface |
| Withdraw/collection jobs | `Batch*WithdrawJob`, `*CollectionJob`, `*SigningRecoveryJob`, `RbfBumpJob`, `FeeRateUpdater` | Fixed job beans route to existing legacy wallet/signer pipeline | Not changed per no-withdraw/collection-core rule |
| Signer services | `wallet-sig1`, `wallet-sig2` | Signer dispatch, HD metadata, decimal conversion | Not changed per no-signature-logic rule |
| Enum definition | `currency-sdks/wallet-common/.../CurrencyEnum.java` | Legacy compatibility enum | Kept as compatibility adapter |
| Old common enum | `backendservices/common/.../CurrencyEnum.java` | Historical common enum | Not used by modern DB Asset Model |

## CurrencyIds Dependency List

| Area | Files | Usage | Replaceable now |
| --- | --- | --- | --- |
| Legacy constants | `backendservices/common/src/main/java/com/surprising/common/config/CurrencyIds.java` | Historical ids only | Kept as legacy mapping |
| SAFE token mapping | `backendservices/common/src/main/java/com/surprising/common/config/SAFETOKEN.java` | Historical SAFE-chain token ids/decimals | Not part of runtime wallet routing |

No runtime BTC/LTC/DOGE/BCH/SOL/TON/APTOS/SUI routing depends on `CurrencyIds`.

## Logic Removed Or Replaced

1. Replaced hardcoded service-layer UTXO checks:
   `BTC || LTC || DOGE || BCH` now resolves through
   `chain_profile.family = 'bitcoin-like'` via `AssetRoutingService`.

2. Replaced service-layer chain key derivation:
   `currency.getName().toUpperCase(...)` in routing paths now resolves through
   `chain_profile.runtime_currency_id -> chain`.

3. Converted scanner checkpoint access for DB-backed UTXO chains:
   `chain_scan_height` is now the primary checkpoint.
   `best_block_height` is used only as one-time legacy seed if no
   `chain_scan_height` row exists.

4. Converted monitor checkpoint source:
   `MonitorCurrencyClient` now reads active `chain_scan_height` rows and no
   longer parses `BestBlockHeight.currency` through `CurrencyEnum`.

5. Converted `/wallet/v1/balance` for DB profile assets:
   balances read from `ledger_balance` by `chain + native_symbol`.
   `currency_balance` remains fallback only for assets without DB profile.

6. Stopped new `currency_balance` writes for DB profile runtimes:
   `AbstractWallet` skips legacy aggregate updates when
   `chain_profile` exists for the runtime currency id.

## Adapter List Kept

| Adapter | Reason |
| --- | --- |
| `AssetRoutingService` | Central compatibility boundary for runtime id to DB profile routing and legacy enum conversion |
| `CurrencyEnum` | Required by legacy wallet beans, fixed jobs, sharded tables, Redis queues, and signer dispatch |
| `CurrencyIds` | Historical SAFE/token mapping only |
| `WithdrawRecordService` / `WithdrawRecordRepository` | External API and notification compatibility |
| `UserAssetService` / `UserAssetRepository` | Legacy mirror used by existing freeze/unfreeze/notification path |
| `CurrencyBalanceService` / `CurrencyBalanceRepository` | Legacy aggregate fallback for non-DB-profile assets |
| `BestBlockHeightService` / `BestBlockHeightRepository` | Old-chain compatibility and one-time checkpoint seeding |
| `wallet-sig1` / `wallet-sig2` signer enums | Signature adapter dispatch; not modified |
| Fixed withdraw/collection job enums | Existing job bean dispatch; not modified |

## Replacement Path

Runtime routing path:

```text
runtime_currency_id
  -> chain_profile
  -> chain + native_symbol + family
  -> chain_asset / token_config
  -> scanner / withdraw / collection / ledger_balance
```

Legacy compatibility path:

```text
external currency id / old table prefix
  -> AssetRoutingService
  -> DB profile when present
  -> CurrencyEnum only for legacy wallet/table/signer adapter
```

Checkpoint path:

```text
chain + scanner_name
  -> chain_scan_height
```

Balance path:

```text
chain + asset_symbol + account_id
  -> ledger_balance
```

## Risk Points

1. `user_asset` writes still exist in withdraw/deposit compatibility flows.
   They are retained to avoid breaking old external APIs and freeze/unfreeze
   behavior. Source of truth remains `ledger_balance`.

2. EVM/TRON/ERC20 old wallet classes still use `CurrencyEnum`.
   The DB-backed EVM/TRON scanners and token registry already use
   `token_config`; old classes are treated as legacy adapter surface.

3. Fixed BTC/LTC/DOGE/BCH withdraw/collection jobs still assign enum constants.
   These are job bean dispatch adapters and were not changed to avoid touching
   stable withdrawal/collection core flow.

4. Full live DB/RPC regression was not enabled in this environment. Tests with
   flags such as `*.db.enabled`, `*.live.enabled`, `evm.fork.enabled`, and
   `bitcoinlike.regtest.enabled` were skipped by JUnit assumptions.

## Regression Test Results

| Command | Result |
| --- | --- |
| `mvn -pl backendservices/wallet-parent/wallet-server -am -DskipTests compile` | PASS |
| `mvn -pl currency-sdks/bitcoin-sdk test` | PASS, 32 tests, 0 failures |
| `mvn -pl backendservices/wallet-parent/wallet-service -am -DskipTests=false test` | PASS, wallet-service 70 tests, 0 failures, 25 skipped; reactor also ran bitcoin-sdk 32 and wallet-common 2 |

Coverage from executed tests:

| Chain / Area | Result |
| --- | --- |
| BTC-style segwit P2WSH SDK | PASS |
| LTC address and fee estimator | PASS |
| DOGE address, params, legacy multisig SDK | PASS |
| BCH address, codec, signing SDK | PASS |
| EVM log scanner unit | PASS |
| TRON address, ABI, gas, scanner, ledger idempotency units | PASS |
| SOL address generation | PASS |
| TON address and message encoding | PASS |
| APTOS address and signing | PASS |
| SUI address and signing | PASS |

Skipped live/DB gates:

| Gate | Reason |
| --- | --- |
| BTC/DOGE/BCH regtest full flow | `bitcoinlike.regtest.enabled` not set |
| Unified UTXO runtime migration DB test | `utxo.migration.db.enabled` not set |
| LTC live flow | `ltc.live.enabled` not set |
| EVM fork/live tests | `evm.fork.enabled`, `evm.multiuser.enabled`, `evm.live.enabled` not set |
| TRON live/deposit/withdraw/collection tests | required live flags/report not enabled |
| SOL/TON/APTOS/SUI DB/live token/native tests | required DB/live flags and seeds not enabled |

## DB Consistency Result

| Check | Result |
| --- | --- |
| Schema/data mutation | PASS: no DDL and no data deletion/rebuild added |
| DB Asset read paths | PASS: new routing reads `chain_profile`, `chain_scan_height`, `ledger_balance` |
| `best_block_height` access for DB-backed UTXO | PASS: only one-time seed fallback; primary path is `chain_scan_height` |
| `currency_balance` for DB profile runtimes | PASS: new reads use `ledger_balance`; legacy writes skipped for DB profile runtimes |
| `ledger_balance` idempotency unit | PASS: `TronLedgerIdempotencyTest` passed |
| Live `ledger_balance == UTXO + account balance` | NOT EXECUTED locally: no DB/RPC environment flags were enabled |
| Scanner replay / withdraw retry live idempotency | NOT EXECUTED locally: live/regtest gates skipped by assumptions |

## Final Assessment

Funding system impact: **NO**

Reason:

- No scanner parser, signing, broadcast, collection builder, chain key, or SDK
  logic was changed.
- No table structure or database data was modified.
- Runtime service/controller/job routing now resolves through DB Asset Model
  where safe.
- Legacy enum/id surfaces are isolated and documented as adapters.
