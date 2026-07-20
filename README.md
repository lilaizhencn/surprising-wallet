# Surprising Wallet

Multi-tenant blockchain custody infrastructure for exchanges, payment products,
commerce platforms, and other applications that need one shared wallet service.

[中文说明](README_CN.md) · [Custody model](docs/en/multi-tenant-custody.md) ·
[API contract](docs/openapi/custody-v1.yaml) · [Documentation](docs/README.md)

## Product boundary

Surprising Wallet owns:

- tenant-isolated deterministic deposit addresses;
- chain scanning, confirmed deposits, balances, withdrawals, and reconciliation;
- tenant-prefunded native-coin Gas accounts with withdrawal fee reservation and settlement;
- signed API requests, replay protection, IP allowlists, Console sessions, and audit logs;
- signed deposit/withdrawal Webhooks with durable per-attempt history, automatic retries,
  and manual replay.

A tenant owns its customers, merchants, orders, and internal balance rules. It
passes an opaque `externalReference` when allocating an address. The wallet does
not create or model that tenant-side user.

```text
tenant credential + chain + externalReference -> one stable deposit address
on-chain deposit -> tenant + externalReference -> signed Webhook
```

Console-created addresses may omit `externalReference`; every such request
allocates a fresh address. Address creation never emits a Webhook. Only deposit
and withdrawal lifecycle events are delivered.

## Repositories

- Backend: this repository
- React + Ant Design Console: [surprising-wallet-web](https://github.com/lilaizhencn/surprising-wallet-web)

## Main modules

| Module | Responsibility |
|---|---|
| `wallet-server` | Custody/Console APIs, jobs, validation, Webhook dispatch |
| `wallet-service` | Chain adapters, scanning, ledger, withdrawal, collection |
| `wallet-sig1`, `wallet-sig2` | Isolated signing services |
| `currency-sdks/*` | Bitcoin-like, TRON, and shared chain/key support |

The runtime supports Bitcoin-like, EVM, TRON, Solana, TON, Aptos, Sui, XRP,
Cardano, Polkadot, NEAR, Monero, HyperEVM, and HyperCore configurations. The
active network and assets are controlled by the database. Existing testnet
assets and test seeds are intentionally preserved.

## Local start

Requirements: JDK 21, Maven, PostgreSQL, and Redis.

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
mvn -pl backendservices/wallet-parent/wallet-server -am package
java -jar backendservices/wallet-parent/wallet-server/target/wallet-server-1.0.0-SNAPSHOT.jar
```

Required custody secrets:

```text
SW_CUSTODY_SECRET_MASTER_KEY   32-byte Base64 or 64-character hex key
SW_CUSTODY_PLATFORM_ADMIN_EMAIL
SW_CUSTODY_PLATFORM_ADMIN_PASSWORD
```

Database, Redis, HTTP, CORS, chain keys, and production startup requirements are
listed in [Startup and testing](docs/en/startup-and-testing.md). Never run the
fresh-database SQL against an existing production database.

## Verification

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am test
```

Live-chain tests are opt-in because they need funded test addresses and external
RPC/faucet availability. See [Scripts and regtest](docs/en/scripts-and-regtest.md).
