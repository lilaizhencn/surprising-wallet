# Database Guide

[中文版本](../zh/database.md)

Database files live under `docs/db/`.

## Files

| File | Purpose |
|---|---|
| `docs/db/surprising-wallet-init-pgsql.sql` | Single fresh local initialization snapshot exported from the current DB Asset Model schema. Includes schema plus static chain/token configuration seed data. |

## Initialization Order

For a new local test database:

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

Use `surprising-wallet-init-pgsql.sql` only on a disposable or fresh database. It contains reset statements from `pg_dump --clean` and is not for in-place production upgrades.

## Seed Data Scope

The initialization file includes static configuration rows for `chain_profile`, `chain_asset`, `token_config`, `wallet_system_config`, `wallet_public_key`, and `chain_rpc_node`.

It does not include runtime rows from address, balance, scan-height, deposit, withdrawal, collection, signing, UTXO, or chain transaction tables.

## DB Asset Model

| Table | Runtime role |
|---|---|
| `chain_profile` | Chain key, family, network, confirmations, scan/withdraw/collection/transfer switches, scan start height, BIP44 coin type |
| `chain_rpc_node` | RPC/fullnode/indexer/faucet nodes per chain/network/environment/purpose, priority, auth, remarks |
| `wallet_system_config` | Global scan/withdraw/collection/transfer switches |
| `wallet_public_key` | Three BIP32 public keys required by wallet-server startup |
| `chain_asset` | Chain-native and chain-scoped asset definitions |
| `token_config` | Token contract, decimals, enabled flag, min deposit/withdraw, collection policy |
| `chain_address` | Address registry for UTXO/account chains; each enabled chain's default hot wallet is fixed to the native-asset `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT` row |
| `chain_scan_height` | Scanner checkpoints |
| `deposit_record` | Normalized deposit events |
| `ledger_balance` | Chain-scoped account balance |
| `utxo_record` | Bitcoin-like UTXO runtime state |

## Ledger Semantics

`ledger_balance` is chain-scoped. A USDT balance on Ethereum and a USDT balance on TRON are different ledger rows because they have different chain execution and settlement semantics.

If a product needs a global USDT display balance, aggregate it in the query/API layer by symbol and account. Do not use a hardcoded currency id to merge runtime execution paths.

## Runtime Tables

Runtime state is stored in `ledger_balance`, `chain_address`, `token_config`,
`chain_scan_height`, and chain-specific transaction tables/services.

## Startup Validation

wallet-server validates at startup:

- `wallet_public_key` slots 1, 2, and 3 must be enabled.
- Each `chain` in `chain_profile` may have only one enabled network.
- With `sw.app.env.name=prod`, enabled profiles may not use testnet/devnet/regtest.
- Every enabled profile must have at least one `chain_rpc_node` for the current environment.
- Every enabled chain must have exactly one default hot wallet address: native-asset `chain_address`, `user_id=0`, `biz=0`, `address_index=0`, `wallet_role=DEPOSIT`. Startup re-derives the address/path and compares them with the database.
- Task switches, scan start, batch size, and RPC node count are logged for every chain. Missing or disabled settings are logged as WARN.

## Permissions

The `wallet` database user needs schema privileges:

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all tables in schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all sequences in schema public to wallet;"
```
