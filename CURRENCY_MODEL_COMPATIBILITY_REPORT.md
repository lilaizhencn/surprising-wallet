# Currency Model Compatibility Report

Generated: 2026-06-21 18:50 Asia/Shanghai.

## Overall Conclusion

- `chain_profile`, `chain_asset`, `token_config`, and the unified operational tables are the source of truth for new chains.
- `com.surprising.wallet.common.currency.CurrencyEnum` remains only as a legacy routing adapter for sharded table names, Redis queues, old jobs, and signer dispatch.
- `com.surprising.common.config.CurrencyIds` is an older, separate compatibility namespace. It must not be used by LTC/DOGE/BCH.
- Runtime currency ids and BIP44 coin types are separate fields in `chain_profile`.
- A migration is required and has been added for `chain_profile` and `utxo_record`, plus chain-scoped withdrawal/collection uniqueness.
- The LTC live gate validated runtime id `24` with BIP44 coin type `2`; deposit, withdrawal, collection, and reconciliation used the database profile successfully.

## Remaining CurrencyEnum Dependencies

The active legacy wallet pipeline still depends on `CurrencyEnum` in these areas:

- Wallet lookup and sharded table prefixes:
  - `WalletContext`
  - `AbstractWallet`
  - `AbstractBtcLikeWallet`
  - `AbstractAccountWallet`
  - `AbstractEthLikeWallet`
  - `BtcWallet`, `LtcWallet`, `EthWallet`, `Erc20Wallet`, `TronWallet`
- Legacy scanner, withdrawal, collection, transfer, fee, and RBF jobs:
  - `AbstractScanBlockJob`
  - `AbstractBatchWithdrawJob`
  - `BatchBtcWithdrawJob`, `BatchLtcWithdrawJob`
  - `BtcCollectionJob`, `LtcCollectionJob`
  - `AbstractTransferJob`, `FeeRateUpdater`, `RbfBumpJob`
- Legacy service/mapper routing:
  - `AddressService`
  - `UtxoTransactionService`
  - `WithdrawTransactionService`
  - `TransactionService`
  - their current implementations
- First/second signature dispatch and HD derivation:
  - sig1 `SignContent`, `FirstSignJob`, `ISignService`, BTC/LTC sign services
  - sig2 `SignContent`, `SecondSignJob`, `TransactionSignService`, `BipNodeUtil`, BTC/LTC sign services
- Legacy monitoring/controller entry points:
  - `MonitorCurrencyClient`
  - `WalletController`

These dependencies are retained because replacing the old queue/table routing in the LTC gate would materially risk BTC. New-chain code must load and validate its database profile before using the matching enum entry.

Current audit scope: 64 Java source files reference `CurrencyEnum`. This is a compatibility surface to reduce over time, not the configuration source for DOGE/BCH.

## Remaining CurrencyIds Dependencies

`CurrencyIds` is currently referenced only by `backendservices/common/.../SAFETOKEN.java` for historical SAFE-chain token mappings. No active LTC wallet code uses it.

Important conflicts:

- `CurrencyIds.LTC = 2`, while the modern wallet uses LTC runtime id `24`.
- `CurrencyEnum.ETH = 2`, so using `CurrencyIds.LTC` in the modern wallet would route LTC as ETH.
- `CurrencyIds.ERC20_AEBT = 24`, so runtime id `24` is safe only inside the modern wallet namespace and must never be cross-resolved through `CurrencyIds`.
- `CurrencyIds.BCH = 5` is legacy-only and must not become the BCH wallet source of truth.

## Database-Driven Logic

- `chain_profile`: runtime currency id, BIP44 coin type, network, confirmation, fee, dust, explorer, and enablement metadata.
- `chain_asset`: native/token asset metadata and transfer/withdraw minimums.
- `token_config`: EVM/TRON token contract, decimal, confirmation, collection, and gas policy.
- `hot_wallet_address`: chain-scoped hot-wallet registration.
- `chain_scan_height`: chain/scanner recovery checkpoint.
- `deposit_record`: chain + txid + vout/log-index idempotency and credit state.
- `utxo_record`: chain + txid + vout source of truth for AVAILABLE/LOCKED/SPENT state.
- `withdrawal_order`: chain-scoped business withdrawal state.
- `collection_record`: chain-scoped collection state.
- `ledger_balance`: chain + asset + account ledger with guarded available/locked/total balances.

LTC continues to mirror legacy `ltc_*`, `user_asset`, `currency_balance`, and `best_block_height` tables until old jobs are replaced.

## LTC Compatibility

- Runtime currency id: `24`, loaded from `chain_profile`.
- BIP44 coin type: `2`, loaded independently from `chain_profile`.
- `CurrencyEnum.LTC` remains as a compatibility entry and is validated against the database profile at startup.
- Signer dispatch still uses enum id `24`; HD derivation uses coin type `2`.
- `CurrencyIds.LTC` must not be used.

## DOGE Compatibility

- Runtime currency id: `41`; the pre-migration database conflict query returned zero rows.
- BIP44 coin type: `3`.
- `CurrencyEnum.DOGE` was added only because old signer/job dispatch requires it.
- The enum value must mirror `chain_profile.runtime_currency_id`; network, fee, dust, confirmations, and RPC must remain database/application driven.
- Do not add or reuse `CurrencyIds` constants.
- DOGE testnet/mainnet profiles and chain-scoped legacy compatibility tables were added.

## BCH Compatibility

- Runtime currency id: `42`; the pre-migration conflict query returned zero rows.
- BIP44 coin type: `145`.
- `CurrencyEnum.BCH` was added only for old signer/job routing.
- Do not use legacy `CurrencyIds.BCH = 5`.
- CashAddr, legacy address compatibility, SIGHASH_FORKID, fee, dust, and confirmations remain BCH-specific profile/signer concerns.

## Migration Requirement

Required and added:

- `chain_profile`
- `utxo_record` with unique `(chain, tx_hash, vout)`
- unique `(chain, order_no)` for `withdrawal_order`
- unique `(chain, collection_no)` for `collection_record`
- LTC, DOGE, and BCH testnet/mainnet `chain_profile` rows
- DOGE/BCH native `chain_asset` rows and chain-scoped compatibility tables

No MBG generation was needed because these tables are accessed through the hand-written JDBC repository; no service, scanner, signer, or fee logic was generated.

## ID Conflict Risk

- Current modern database runtime ids are `1` (BTC), `24` (LTC), `41` (DOGE), and `42` (BCH).
- Current `chain_profile` rows reserve runtime ids `24`, `41`, and `42` for LTC, DOGE, and BCH testnet/mainnet profiles. Pre-migration checks found no conflicting use in the legacy balance tables.
- The legacy `CurrencyIds` namespace contains incompatible meanings for ids `2`, `5`, `24`, and many token ids.
- Risk is high if code imports both currency namespaces or hardcodes ids.
- Mitigation:
  - database profile lookup and startup validation;
  - no new `CurrencyIds` usage;
  - one compatibility enum entry per new chain;
  - migrations query/reserve runtime ids before DOGE/BCH activation;
  - BIP44 coin type never inferred from runtime id.
