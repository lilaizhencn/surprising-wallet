# Regression Report

Generated: 2026-06-20 14:51 Asia/Shanghai.

## Overall Result

- `mvn clean install -DskipTests=false`: passed across 14 modules.
- `wallet-sig1`: started, first-sign job active, stopped cleanly.
- `wallet-sig2`: started, second-sign job active, stopped cleanly.
- `wallet-server`: started, PostgreSQL and Redis connected, BTC scan/collection jobs active, stopped cleanly.
- Commit: not created because several user-defined commit gates remain blocked.

## BTC Regression

- BTC address generation/witnessScript shape: covered by existing P2WSH tests.
- BTC SegWit multisig tests: 18 passed.
- BTC UTXO optimizer tests: 3 passed.
- BTC scan from DB height `5010273` advanced to `5010490`.
- New detected BTC deposit: `4fa59a278c4b94674a387a599627c2c1aa657c84c57f67f5f7289cfe931826b9-0`, amount `0.00200737 BTC`, userId `1`, credited once.
- User asset after credit: `0.00328103 BTC`.
- Duplicate check: one row for `(tx_id, seq)`.
- Collection created and broadcast: `5c5363bced47a9c0ee598357a90ce2a65dd39d2b8671987fd95794c397b05a5d`.
- Collection fee: `318 sats`, estimated `159 vB/634 wu`, actual `158 vB/630 wu`.
- Collection broadcast status: mempool accepted, not yet confirmed at report time.
- Redis signing/broadcast queues checked: length `0`.

## BTC 0.00001 Faucet Check

- All current `btc_address` rows were checked through testnet explorer API.
- No `1000 sats` output was found for the current DB address set.
- The specified `addressA` value is not present in code or database, so the exact `0.00001 BTC` assertion is blocked until the address or faucet txid is provided or the faucet transaction appears on-chain.

## Multi-Chain Review

- Production Tatum dependency: none found. Tatum helper is under `src/test`.
- EVM/TRON generic adapter scans were changed from silent empty returns to fail-fast `UnsupportedOperationException` until real RPC scanner runtimes are wired.
- Production token registry now has a `JdbcTokenRegistry` primary implementation and reads `token_config` first, then legacy `token_registry`.
- TRON legacy gRPC config was preserved for startup compatibility; PublicNode HTTP endpoint is kept in `atomex.chains.tron-mainnet`.

## Database

- `multi-chain-wallet-schema.sql` adds `chain_asset`, `token_config`, `chain_scan_height`, `hot_wallet_address`, `deposit_record`, `withdrawal_order`, `evm_nonce`, `evm_transaction`, `evm_token_transfer`, `tron_transaction`, `tron_token_transfer`, `sol_transaction`, `ton_transaction`, and compatibility `token_registry`, `evm_tx`, `tron_tx`, `ledger_balance`.
- Applying the SQL was blocked locally: `wallet` user has no `CREATE` privilege on schema `public`.
- Required DBA action: grant schema create or run migration with an owner role.

## Blocked Items

- EVM/TRON/SOL/TON live transfers were not executed.
- SOL/TON runtime connectors remain blocked/fail-fast.
- Multi-user BTC withdrawal scenario was not executed in this run.
- New multi-chain DB tables were not applied because of local DB permissions.
- No commit was created because not all required gates passed.
