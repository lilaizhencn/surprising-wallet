# Bitcoin Cash Wallet Report

Generated: 2026-06-21 23:23 Asia/Shanghai.

## Overall Conclusion

- Bitcoin Cash was integrated on bitcoinj `0.17.1` with a minimal BCH-specific signer.
- CashAddr generation/parsing, legacy compatibility, P2SH 2-of-3, `SIGHASH_ALL|FORKID (0x41)`, scanning, withdrawal, collection, recovery, unified UTXO state, and ledger settlement are implemented.
- Real BCH testnet RPC reads and a real block scan passed.
- Funded live deposit/withdraw/collection is deferred because no BCH testnet funds were available, per the updated task instruction.
- Full Maven regression passed; no push.

## Files / Schema / MBG

- Added BCH address codec, network parameters, fee policy, transaction signer, wallet, RPC command, scanner, withdrawal/collection/recovery jobs, signer services, and tests.
- Added `bch_address`, `bch_utxo_transaction`, `bch_withdraw_record`, `bch_withdraw_transaction`.
- Reused unified `chain_profile`, `chain_asset`, `chain_scan_height`, `utxo_record`, `deposit_record`, `withdrawal_order`, `collection_record`, and `ledger_balance`.
- Incremental and clean-database migrations passed.
- No MBG generation was required.
- No files were deleted.

## Currency Model

- Runtime currency id: `42`.
- BIP44 coin type: `145`.
- Pre-migration checks found no id `42` collision.
- `CurrencyEnum.BCH` is legacy routing only; `CurrencyIds.BCH = 5` is not used.
- Database/application configuration remains the source of truth.

## Network / Address

- Mainnet legacy P2PKH/P2SH/WIF: `0 / 5 / 128`; CashAddr prefix `bitcoincash`.
- Testnet legacy P2PKH/P2SH/WIF: `111 / 196 / 239`; CashAddr prefix `bchtest`.
- Wallet and both signer services select mainnet/testnet parameters from their active Spring configuration; no signer path is fixed to testnet.
- Deposit: `bchtest:pzleucus9lj0zns4j52mkecpams5hftrzqfaauzp8t`.
- Collection source: `bchtest:ppycmnpzszs2j50yerxelncchketwgsgwq0er5k06l`.
- Hot: `bchtest:prtahgvp3xhdjpp2xkyvn6rxe9v3r6ceqy4hf4lyap`.
- Paths use `m/44/145/...`.
- CashAddr ↔ legacy round-trip tests passed.

## bitcoinj 0.17.1 Support Boundary

- Reused bitcoinj transaction serialization, P2SH scripts, keys, DER signatures, and BIP143-style digest primitive.
- Added only the BCH-specific `0x41` hash type and CashAddr codec.
- Every generated signature was verified against the BCH digest and checked to end in `0x41`.
- No conflicting BCH/UTXO SDK was introduced.

## Fee / Dust

- Default fee: `1 sat/byte`.
- Dust threshold: `546 sat`.
- P2SH size estimation and dust rejection are chain-specific.

## Real Testnet Validation

- RPC: `https://bitcoin-cash-testnet.gateway.tatum.io`.
- `getblockcount`: passed.
- Scanned block: `1715780`.
- Block hash: `000000003a87711566cfabbc64d13b1088521700f15b1221d50a16ef3a5b7347`.
- Sample tx: `efd348e353e67d62c508dee30b2147f7f667b57c4238c0643f05177286563323`.
- Scanner checkpoint advanced to `1715780`, safe height `1715779`.
- The block contained no platform output; `deposit_record` and UTXO rows remained zero.

## Records / Ledger / Idempotency / Recovery

- Live deposit, withdrawal, and collection txids: deferred pending testnet funds.
- PostgreSQL-backed synthetic flow verified BCH deposit credit exactly once, one UTXO lock winner, guarded release, and zero negative ledger rows.
- Chain-scoped uniqueness remains `(chain, txid, vout)` and `(chain, order/collection no)`.
- Stale BCH signing rows use an atomic database claim before Redis requeue.
- Failure paths release unified/legacy UTXOs and frozen balances.
- Two users (`9201`, `9202`) produced distinct addresses; funded multi-user flow is deferred.

## Tests

- `mvn -q clean install -DskipTests=false`: passed.
- Surefire: 71 tests, 0 failures, 0 errors, 11 skipped.
- BCH CashAddr/FORKID signing tests: passed.
- BCH address generation: passed.
- PostgreSQL-backed BCH idempotency/ledger/UTXO test: passed.
- wallet-server health: `UP`.
- wallet-sig1 and wallet-sig2: started.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- BTC/LTC/DOGE/EVM/TRON regression passed in the full reactor.

## Deferred / Risks

- Funded BCH live deposit, withdrawal, collection, on-chain confirmation settlement, and live reconciliation are deferred.
- Public RPC is rate-limited; production requires controlled BCH Node infrastructure.
- CashAddr support currently targets standard 160-bit P2PKH/P2SH, which is the wallet's supported scope.

## Commit / Push

- Commit message: `feat: add bitcoin cash wallet flow`.
- Commit hash: this report is included in the commit.
- Push: no.

## Testnet Funding Request

Send at least `0.02 tBCH` to:

`bchtest:pzleucus9lj0zns4j52mkecpams5hftrzqfaauzp8t`

This is sufficient for deposit, withdrawal, collection, fee/dust, and reconciliation validation in the next live-gate task.
