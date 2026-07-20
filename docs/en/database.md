# Database Guide

[中文版本](../zh/database.md)

Database files live under `docs/db/`.

## Files

| File | Purpose |
|---|---|
| `docs/db/surprising-wallet-init-pgsql.sql` | Protected fresh-database chain schema and test configuration seed. |
| `wallet-server/src/main/resources/db/custody-schema.sql` | Additive, idempotent multi-tenant custody schema applied by wallet-server at startup. |

## Initialization Order

For a new local test database:

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

Use `surprising-wallet-init-pgsql.sql` only on a disposable or fresh database. It contains reset statements from `pg_dump --clean` and is not for in-place production upgrades.

When wallet-server starts, Spring applies `custody-schema.sql`. Running it again is safe.

## Seed Data Scope

The initialization file includes static configuration rows for `chain_profile`, `chain_asset`, `token_config`, `wallet_system_config`, and `chain_rpc_node`. It creates `wallet_key_config` without inserting any seed.

The initialization file may keep disabled RPC and token template rows. At the end of the script, any `chain_rpc_node`, `token_config`, or `chain_asset` row that still contains placeholder URLs, credentials, or token contracts is forced disabled/inactive. Store real values in the target environment database before enabling the node, token, or asset.

It does not include runtime rows from address, balance, scan-height, deposit, withdrawal, collection, signing, UTXO, or chain transaction tables.

## DB Asset Model

| Table | Runtime role |
|---|---|
| `chain_profile` | Chain key, family, network, confirmations, scan/withdraw/collection/transfer switches, scan start height, BIP44 coin type |
| `chain_rpc_node` | RPC/fullnode/indexer/faucet nodes per chain/network/environment/purpose, priority, auth, request pacing, remarks |
| `wallet_system_config` | Global scan/withdraw/collection/transfer switches and optional withdrawal admin approval gate |
| `wallet_key_config` | Singleton atomic keyset containing the sig1/sig2/recovery BIP32 seeds and one Ed25519 seed; currently plaintext Base64 |
| `chain_asset` | Chain-native and chain-scoped asset definitions |
| `token_config` | Token contract, decimals, enabled flag, min deposit/withdraw, collection policy |
| `chain_address` | Address registry for UTXO/account chains; each enabled chain's default hot wallet is fixed to the native-asset `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT` row |
| `chain_scan_height` | Scanner checkpoints |
| `deposit_record` | Normalized deposit events |
| `withdrawal_review_audit` | Admin approval/rejection audit trail for withdrawals |
| `ledger_balance` | Chain-scoped account balance |
| `utxo_record` | Bitcoin-like UTXO runtime state |

## Custody tables

| Table | Role |
|---|---|
| `custody_tenant`, `custody_tenant_user`, `custody_session` | Tenant and Console identity |
| `custody_api_key`, `custody_api_nonce`, `custody_ip_rule` | Signed API authentication and network policy |
| `custody_address` | Tenant-to-chain-address allocation; one chain address can belong to only one tenant |
| `custody_deposit`, `custody_withdrawal` | Tenant-scoped transfer projections |
| `custody_ledger_entry` | Immutable custody event ledger used for reconciliation |
| `custody_event`, `custody_webhook_delivery`, `custody_webhook_endpoint` | Durable Webhook fan-out and delivery state |
| `custody_idempotency_key` | Address and permanent withdrawal request idempotency |
| `custody_audit_log` | Tenant operational/security audit trail |

## Ledger Semantics

`ledger_balance` is chain-scoped. A USDT balance on Ethereum and a USDT balance on TRON are different ledger rows because they have different chain execution and settlement semantics.

If a product needs a global USDT display balance, aggregate it in the query/API layer by symbol and account. Do not use a hardcoded currency id to merge runtime execution paths.

## Runtime Tables

Runtime state is stored in `ledger_balance`, `chain_address`, `token_config`,
`chain_scan_height`, and chain-specific transaction tables/services.

## Startup Validation

wallet-server validates at startup:

- When `wallet_key_config` exists, all four values must be different Base64-encoded 32-byte seeds.
- Each chain may enable only one network at a time.
- Non-production environments may store devnet and testnet profiles together and switch the enabled profile for each test scenario; `sw.app.env.name=prod` permits only production networks.
- Every enabled profile must have its required `chain_rpc_node` purposes for the current environment. For example, DOT requires `rpc` and `runtime`, and also `asset_rpc` when DOT tokens are enabled.
- XMR `regtest` additionally requires `rpc`, `faucet`, and `daemon` nodes so the non-production test coin flow is available immediately after startup.
- wallet-server may start without a keyset so the platform administrator can configure it, but derivation and signing remain unavailable; sig1/sig2 must start after the keyset is configured.
- Enabled `chain_rpc_node` rows must not contain placeholder URL or credential values such as `CHANGE_ME`, `YOUR_*`, or `REPLACE_ME`.
- Enabled `token_config` rows and active non-native `chain_asset` rows must have real contract addresses or asset ids; empty or placeholder contracts are rejected.
- When an enabled `token_config.network` is set, it must match any enabled `chain_profile.network` for the same chain.
- Every active non-native `chain_asset` must have a matching enabled `token_config` row for the same `chain` and `symbol`, and both rows must use the same contract address or asset id. This prevents the wallet page from exposing a token that scanners or withdrawal services cannot resolve.
- Every enabled chain must have exactly one default hot wallet address: native-asset `chain_address`, `user_id=0`, `biz=0`, `address_index=0`, `wallet_role=DEPOSIT`. Startup re-derives the address/path and compares them with the database.
- Task switches, scan start, batch size, and RPC node count are logged for every chain. Missing or disabled settings are logged as WARN.

## Permissions

The `wallet` database user needs schema privileges:

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all tables in schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all sequences in schema public to wallet;"
```
