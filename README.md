# Surprising Wallet

Multi-chain wallet backend for exchange-style custody flows. The current runtime is centered on the DB Asset Model:

- `chain_profile` for chain routing and network metadata
- `chain_asset` for native assets
- `token_config` for token runtime configuration
- `ledger_balance` for user and system balances

English documentation lives under [`docs/en`](docs/en). Chinese documentation lives under [`docs/zh`](docs/zh).

[中文说明](README_CN.md)

## Current Scope

Supported chain families:

| Family | Chains | Runtime model |
|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | UTXO, local regtest, 2-of-3 signing |
| EVM | Ethereum, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche | Account model, ERC20, Hardhat fork tests |
| TRON | TRON Nile/mainnet profile | Account model, TRC20 |
| Ed25519 account chains | SOL, TON, APTOS, SUI | Account/token services, DB tests, external devnet/testnet live tests |

## Quick Start

Prerequisites:

- JDK 17+
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker for local BTC/LTC/DOGE/BCH regtest
- Node.js 18+ for EVM fork tests

Create the database:

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

Initialize a fresh test database:

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

The initialization SQL includes `chain_profile`, `chain_rpc_node`, `wallet_system_config`, and `wallet_public_key`. Chain switches, scan start, scan batch size, RPC nodes, and the three wallet-server public keys are read from the database.

Build:

```bash
mvn clean install -DskipTests
```

Start the services from the repository root:

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

Important runtime secrets:

```bash
export SW_DB_PASSWORD='<PostgreSQL password>'
export SW_SIG1_MASTER_KEY='<BIP32 tprv for signer 1>'
export SW_SIG2_MASTER_KEY='<BIP32 tprv for signer 2>'
export SW_ED25519_SEED='<32-byte Ed25519 seed in hex or base64>'
```

For production, keep the third BIP32 private root offline and configure only the three public keys in the wallet server.
The three wallet-server public keys are configured in `wallet_public_key`, not YAML/env.

## Test Environment

Show the available test matrix:

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

Run DB-only account-chain tests:

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

Run local BTC/LTC/DOGE/BCH regtest:

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

Run EVM fork tests:

```bash
cd evm-fork
npm install
cd ..
scripts/regtest/all-chain-regtest.sh test-evm
```

External live/devnet spending tests require funded test wallets and RPC/faucet availability:

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
```

## Documentation

| Topic | English | Chinese |
|---|---|---|
| Documentation index | [docs/README.md](docs/README.md) | [docs/README_CN.md](docs/README_CN.md) |
| Startup and testing | [docs/en/startup-and-testing.md](docs/en/startup-and-testing.md) | [docs/zh/startup-and-testing.md](docs/zh/startup-and-testing.md) |
| Database | [docs/en/database.md](docs/en/database.md) | [docs/zh/database.md](docs/zh/database.md) |
| Architecture | [docs/en/architecture.md](docs/en/architecture.md) | [docs/zh/architecture.md](docs/zh/architecture.md) |
| Runtime flow | [docs/en/system-code-flow.md](docs/en/system-code-flow.md) | [docs/zh/system-code-flow.md](docs/zh/system-code-flow.md) |
| Regtest scripts | [docs/en/scripts-and-regtest.md](docs/en/scripts-and-regtest.md) | [docs/zh/scripts-and-regtest.md](docs/zh/scripts-and-regtest.md) |
| EVM fork | [docs/en/evm-fork-testing.md](docs/en/evm-fork-testing.md) | [docs/zh/evm-fork-testing.md](docs/zh/evm-fork-testing.md) |
| Infrastructure | [docs/en/infra.md](docs/en/infra.md) | [docs/zh/infra.md](docs/zh/infra.md) |

## Repository Layout

```text
backendservices/                 Java backend services
currency-sdks/                   Chain SDK and shared wallet libraries
docs/                            Documentation, SQL schema, document assets
docs/db/                         Database initialization and historical backups
evm-fork/                        Hardhat fork runtime, kept at root for scripts/tests
infra/                           Docker and mock coin infrastructure
scripts/                         Regtest and migration scripts, kept at root for tests
```
