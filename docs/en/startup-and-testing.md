# Startup and Testing Guide

[中文版本](../zh/startup-and-testing.md)

This guide describes how to configure the project, start the local services, and run the test environments.

## 1. Prerequisites

Install:

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker for BTC/LTC/DOGE/BCH regtest nodes
- Node.js 18+ and npm for EVM fork tests

Check versions:

```bash
java -version
mvn -version
psql --version
redis-server --version
docker --version
node --version
npm --version
```

## 2. Database Setup

Create the database and user:

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

Apply the single initialization file for a fresh local test database:

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

Important:

- `docs/db/surprising-wallet-init-pgsql.sql` is the only DB initialization file. It is exported from the current local DB schema and includes static chain/token configuration seed data.
- It is for fresh local databases and contains destructive reset behavior.
- Do not run destructive SQL on production or shared environments.

## 3. Redis

Start Redis locally:

```bash
redis-server
```

The default local configuration expects Redis on `127.0.0.1:6379`.

## 4. Build

From the repository root:

```bash
mvn clean install -DskipTests
```

For a faster compile check:

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am test -DskipTests
```

## 5. Keys and Runtime Configuration

Bitcoin-like chains, EVM, and TRON use BIP32/secp256k1 roots.

```bash
export SW_SIG1_MASTER_KEY='<BIP32 tprv for signer 1>'
export SW_SIG2_MASTER_KEY='<BIP32 tprv for signer 2>'
```

The wallet server reads the three public roots from `wallet_public_key`. The initialization SQL seeds the current test public keys. In production, replace slots 1/2/3 with production xpub values and keep them `enabled=true`. Startup fails when any required slot is missing.

SOL/TON/APTOS/SUI use one Ed25519 master seed:

```bash
export SW_ED25519_SEED='<32-byte hex or base64 seed>'
```

For local tests, `application-test.yaml` contains a fallback Ed25519 seed. Production must use an environment secret instead.

Common wallet-server environment variables:

```bash
export SW_DB_PASSWORD='<PostgreSQL password>'
export SW_ED25519_SEED='<32-byte Ed25519 seed in hex or base64>'
export SW_WALLET_ADMIN_USERNAME='<wallet admin username>'
export SW_WALLET_ADMIN_PASSWORD='<wallet admin password>'
```

Common signer-service environment variables:

```bash
export SW_SIG1_MASTER_KEY='<BIP32 tprv for signer 1>'
export SW_SIG2_MASTER_KEY='<BIP32 tprv for signer 2>'
```

Chain runtime configuration no longer comes from YAML/env:

| Setting | Database source |
|---|---|
| Global scan/withdraw/collection/transfer switches | `wallet_system_config` |
| Per-chain scan/withdraw/collection/transfer switches | `chain_profile.scan_enabled/withdraw_enabled/collection_enabled/transfer_enabled` |
| Per-chain scan start height and per-run scan cap | `chain_profile.scan_start_height/scan_max_blocks_per_run` |
| Per-chain scan batch size | `chain_profile.scan_batch_size` |
| Chain network, confirmations, chain ID, gas policy | `chain_profile` |
| RPC/fullnode/indexer/faucet nodes | `chain_rpc_node` |
| Three wallet-server public keys | `wallet_public_key` |

The TokDou wallet page reads wallet-server:

| Scenario | API base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/deploy | `https://api.tokdou.com` |
| temporary override | `VITE_WALLET_API_BASE=https://... npm run dev` |

## 6. Application Configuration

Main files:

| File | Purpose |
|---|---|
| `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml` | Local wallet-server config |
| `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml` | Test profile config |
| `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml` | Production placeholders |
| `backendservices/wallet-sig1/src/main/resources/application.yaml` | First signer config |
| `backendservices/wallet-sig2/src/main/resources/application.yaml` | Second signer config |

Required local settings:

- PostgreSQL URL/user/password
- Redis host/port
- Only one enabled network per chain in `chain_profile`
- At least one enabled `chain_rpc_node` for every enabled chain and current `sw.app.env.name`
- Enabled `wallet_public_key` slots 1/2/3
- Signer private roots
- `SW_ED25519_SEED` for Ed25519 chains
- `SW_WALLET_ADMIN_USERNAME` and `SW_WALLET_ADMIN_PASSWORD` for the wallet admin page

Startup validation logs every chain network, task switch, scan start, batch size, and RPC node count. Missing or disabled settings are logged as WARN. Production startup fails if any enabled profile uses testnet/devnet/regtest.

## 7. Start Services

Use three terminals from the repository root.

Terminal 1:

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
```

Terminal 2:

```bash
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
```

Terminal 3:

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

Default ports:

| Service | Port |
|---|---:|
| `wallet-server` | 8002 |
| `wallet-sig1` | 8004 |
| `wallet-sig2` | 8081 |

## 8. Test Matrix

Show supported test environments:

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

Expected coverage:

| Command | Coverage |
|---|---|
| `test-db` | DB-only scanner/ledger/flow tests for SOL/TON/APTOS/SUI/DOGE and UTXO migration |
| `test-utxo` | Local BTC/LTC/DOGE/BCH regtest flow, concurrency, and broadcast tests |
| `test-evm` | EVM fork tests for ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C |
| `test-live` | External testnet connectivity and optional spending tests |
| `test-all` | UTXO, EVM, DB tests, and optional live tests |

Current automated-test boundary:

- `test-utxo` simulates address creation, deposit scanning/crediting, collection, withdrawal, UTXO lock/selection, two signatures, broadcast, and confirmation on local BTC/LTC/DOGE/BCH regtest.
- `test-evm` simulates EVM native/ERC20 deposit, withdrawal, collection, and confirmation flows on Hardhat forks.
- `test-live` checks external testnet/devnet connectivity by default; real spending/broadcast requires `RUN_LIVE_SPENDING=true`, test funds, signer private roots, and the Ed25519 seed.
- Production-style all-chain end-to-end rehearsal still requires wallet-sig1, wallet-sig2, and wallet-server to run together, with requests triggered by the frontend or APIs.

## 9. Run DB-Only Tests

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

These tests do not need local chain nodes. They do need the local PostgreSQL database.

## 10. Run Local UTXO Regtest

Start local nodes:

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
```

Run full local UTXO flow:

```bash
scripts/regtest/all-chain-regtest.sh test-utxo
```

Tune broadcast pressure:

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
scripts/regtest/all-chain-regtest.sh test-utxo
```

Stop or reset nodes:

```bash
scripts/regtest/all-chain-regtest.sh stop
scripts/regtest/all-chain-regtest.sh reset
```

## 11. Run EVM Fork Tests

Install dependencies:

```bash
cd evm-fork
npm install
cd ..
```

Run:

```bash
scripts/regtest/all-chain-regtest.sh test-evm
```

Run selected chains:

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

## 12. Run External Live Tests

Connectivity only:

```bash
RUN_LIVE=true scripts/regtest/all-chain-regtest.sh test-all
```

Connectivity plus spending:

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
```

Live spending requires funded wallets and reliable RPC/faucet access:

- TRON Nile needs funded TRX/TRC20 test accounts.
- SOL devnet may need manual funding when `requestAirdrop` is rate-limited.
- TON full flow requires at least 1 testnet TON on the derived owner address.
- Aptos devnet may need a less rate-limited RPC endpoint.
- Sui testnet faucet is rate-limited and token tests need `SUI_MOCK_COIN_TYPE`.

## 13. Useful Troubleshooting

PostgreSQL permission error:

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

No tests matching pattern:

```bash
mvn ... -Dsurefire.failIfNoSpecifiedTests=false
```

EVM fork port already in use:

```bash
lsof -ti tcp:8545 | xargs kill
```

External RPC rate limit:

- Use a private RPC endpoint through the matching environment variable.
- Retry after faucet/RPC cooldown.
- Prefer DB-only tests for deterministic CI.
