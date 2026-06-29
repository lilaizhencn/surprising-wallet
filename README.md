# Surprising Wallet

Multi-chain custodial wallet backend for exchange-style wallet flows. It provides
deterministic address generation, deposit scanning, user ledger balances,
withdrawal, internal transfer, collection, signer separation, and DB-driven chain
configuration.

[Chinese README](README_CN.md) | [Architecture](docs/en/architecture.md) | [Database](docs/en/database.md) | [Startup and testing](docs/en/startup-and-testing.md)

## Highlights

- Wallet app APIs for registration, login, portfolio, deposit address, withdrawal,
  internal transfer, and non-production faucet flows.
- DB Asset Model: runtime behavior comes from `chain_profile`, `chain_rpc_node`,
  `chain_asset`, `token_config`, `wallet_system_config`, and `wallet_public_key`.
- Multi-chain scanners normalize deposits into `deposit_record` and credit
  `ledger_balance` idempotently.
- Withdrawal and collection flows resolve the active asset from DB before signing,
  broadcasting, and confirming transactions.
- Environment isolation: `dev`, `test2`, and `prod` RPC nodes, scan switches, and
  asset switches are isolated by database rows. The running Spring profile only
  queries nodes for its own environment.
- Startup validation checks public keys, enabled networks, RPC availability,
  default hot wallets, token mappings, and unsafe production/testnet combinations.

## Supported Coins

The badges below show the chains and assets that the project has integrated or
seeded into the runtime model. Actual wallet-page visibility is controlled by
`chain_profile.enabled`, `chain_asset.active`, and `token_config.enabled` in the
target database.

**Native and chain assets**

<p>
  <img alt="BTC Bitcoin" src="https://img.shields.io/badge/BTC-Bitcoin-F7931A?style=flat-square&logo=bitcoin&logoColor=white">
  <img alt="LTC Litecoin" src="https://img.shields.io/badge/LTC-Litecoin-345D9D?style=flat-square&logo=litecoin&logoColor=white">
  <img alt="DOGE Dogecoin" src="https://img.shields.io/badge/DOGE-Dogecoin-C2A633?style=flat-square&logo=dogecoin&logoColor=white">
  <img alt="BCH Bitcoin Cash" src="https://img.shields.io/badge/BCH-Bitcoin%20Cash-0AC18E?style=flat-square&logo=bitcoincash&logoColor=white">
  <img alt="ETH Ethereum" src="https://img.shields.io/badge/ETH-Ethereum-627EEA?style=flat-square&logo=ethereum&logoColor=white">
  <img alt="BNB Chain" src="https://img.shields.io/badge/BNB-BNB%20Chain-F0B90B?style=flat-square&logo=bnbchain&logoColor=white">
  <img alt="MATIC Polygon" src="https://img.shields.io/badge/MATIC-Polygon-8247E5?style=flat-square&logo=polygon&logoColor=white">
  <img alt="ETH Arbitrum" src="https://img.shields.io/badge/ETH_ARB-Arbitrum-28A0F0?style=flat-square&logo=arbitrum&logoColor=white">
  <img alt="ETH Optimism" src="https://img.shields.io/badge/ETH_OP-Optimism-FF0420?style=flat-square&logo=optimism&logoColor=white">
  <img alt="ETH Base" src="https://img.shields.io/badge/ETH_BASE-Base-0052FF?style=flat-square&logo=base&logoColor=white">
  <img alt="AVAX C-Chain" src="https://img.shields.io/badge/AVAX_C-Avalanche-E84142?style=flat-square&logo=avalanche&logoColor=white">
  <img alt="TRX TRON" src="https://img.shields.io/badge/TRX-TRON-EB0029?style=flat-square&logo=tron&logoColor=white">
  <img alt="SOL Solana" src="https://img.shields.io/badge/SOL-Solana-14F195?style=flat-square&logo=solana&logoColor=111111">
  <img alt="TON" src="https://img.shields.io/badge/TON-TON-0098EA?style=flat-square&logo=ton&logoColor=white">
  <img alt="APT Aptos" src="https://img.shields.io/badge/APT-Aptos-111111?style=flat-square&logo=aptos&logoColor=white">
  <img alt="SUI" src="https://img.shields.io/badge/SUI-Sui-4DA2FF?style=flat-square&logo=sui&logoColor=white">
  <img alt="XRP" src="https://img.shields.io/badge/XRP-XRP-23292F?style=flat-square&logo=ripple&logoColor=white">
  <img alt="ADA Cardano" src="https://img.shields.io/badge/ADA-Cardano-0033AD?style=flat-square&logo=cardano&logoColor=white">
  <img alt="DOT Polkadot" src="https://img.shields.io/badge/DOT-Polkadot-E6007A?style=flat-square&logo=polkadot&logoColor=white">
  <img alt="NEAR" src="https://img.shields.io/badge/NEAR-NEAR-000000?style=flat-square&logo=near&logoColor=white">
  <img alt="XMR Monero" src="https://img.shields.io/badge/XMR-Monero-FF6600?style=flat-square&logo=monero&logoColor=white">
  <img alt="HYPE HyperEVM" src="https://img.shields.io/badge/HYPE-HyperEVM-00C6B3?style=flat-square&logo=ethereum&logoColor=white">
  <img alt="MNT Mantle" src="https://img.shields.io/badge/MNT-Mantle-000000?style=flat-square&logo=mantle&logoColor=white">
  <img alt="ETH Linea" src="https://img.shields.io/badge/ETH_LINEA-Linea-121212?style=flat-square&logo=ethereum&logoColor=white">
  <img alt="ETH Scroll" src="https://img.shields.io/badge/ETH_SCROLL-Scroll-EBC28E?style=flat-square&logo=scroll&logoColor=111111">
  <img alt="ETH Unichain" src="https://img.shields.io/badge/ETH_UNICHAIN-Unichain-FF007A?style=flat-square&logo=uniswap&logoColor=white">
</p>

**Token and exchange-account assets**

<p>
  <img alt="USDT" src="https://img.shields.io/badge/USDT-Tether-26A17B?style=flat-square&logo=tether&logoColor=white">
  <img alt="USDC" src="https://img.shields.io/badge/USDC-Stablecoin-2775CA?style=flat-square&logo=usdcoin&logoColor=white">
  <img alt="MUSD" src="https://img.shields.io/badge/MUSD-Mock%20USD-6C5CE7?style=flat-square">
  <img alt="SUI TESTCOIN" src="https://img.shields.io/badge/TESTCOIN-Sui%20Mock-4DA2FF?style=flat-square&logo=sui&logoColor=white">
  <img alt="HIP-1" src="https://img.shields.io/badge/HIP--1-HyperCore%20Spot-00C6B3?style=flat-square">
</p>

## Chain Matrix

| Family | Chains | Asset/token model | Runtime notes |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Native UTXO assets | 2-of-3 signing, local regtest support, external RPC by DB configuration |
| EVM | ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM, MANTLE, LINEA, SCROLL, UNICHAIN | Native gas asset plus ERC20 | Shared EVM scanner, signer, withdrawal, collection, and Hardhat fork tests |
| TRON | TRON | TRX plus TRC20 | Nile/testnet and mainnet profiles are DB-configured |
| Solana | SOLANA | SOL plus SPL tokens | Uses Ed25519 derivation and SPL token account handling |
| TON | TON | TON plus Jetton | Uses Ed25519 derivation and Jetton wallet resolution |
| Aptos | APTOS | APT plus Aptos coin/FA resources | Uses Ed25519 derivation and account-resource token lookup |
| Sui | SUI | SUI plus Sui coin objects | Uses Sui RPC/gRPC-compatible runtime path and coin-object handling |
| XRP Ledger | XRP | XRP plus issued currencies such as USDC | Supports trustline-aware token handling |
| Cardano | ADA | ADA plus native-asset templates | Seeded as disabled templates until exact token/RPC settings are enabled |
| Polkadot | DOT | DOT plus Asset Hub assets | Westend/Asset Hub paths and runtime helper integration |
| NEAR | NEAR | NEAR plus NEP-141 tokens | Uses implicit account derivation and storage-deposit handling |
| Monero | XMR | XMR | Regtest/stagenet/mainnet profiles, wallet-rpc integration, disabled until explicitly configured |
| HyperCore | HYPERCORE | Core USDC, HYPE, HIP-1 spot assets | Uses Hyperliquid `/info` and `/exchange`, not EVM JSON-RPC |

## Repository Layout

```text
backendservices/
  wallet-parent/wallet-server      REST APIs, scheduled jobs, startup validation
  wallet-parent/wallet-service     chain adapters, scanners, ledger, withdrawal, collection
  wallet-sig1                      first online signer for BTC-like 2-of-3 flows
  wallet-sig2                      second signer plus EVM/TRON signing path
  wallet-sig-api                   shared signer contracts

currency-sdks/
  bitcoin-sdk                      Bitcoin-like transaction/address/script support
  tron-sdk                         TRON SDK integration
  wallet-common                    shared key, chain, and runtime utilities
  wallet-client                    client interfaces

docs/                              English/Chinese docs, diagrams, DB init SQL
docs/db/surprising-wallet-init-pgsql.sql
evm-fork/                          Hardhat fork runtime for EVM regression tests
infra/                             local chain and service infrastructure
scripts/                           regtest and integration-test helpers
```

## Runtime Model

The wallet does not route behavior through legacy numeric currency IDs. Runtime
selection is based on chain and asset metadata:

| Table | Purpose |
|---|---|
| `chain_profile` | Chain key, network, family, confirmations, scan/withdraw/collection/transfer switches, BIP44 coin type |
| `chain_rpc_node` | RPC/indexer/faucet nodes, environment tag, priority, authentication, request spacing |
| `chain_asset` | Native assets and chain-scoped token assets exposed to the wallet app |
| `token_config` | Token contract/asset id, decimals, minimums, gas policy, collection policy |
| `wallet_public_key` | Public BIP32 roots used by wallet-server to validate deterministic addresses |
| `wallet_system_config` | Global runtime switches and chain-specific operational settings |
| `chain_scan_height` | Scanner checkpoints per chain/profile |
| `ledger_balance` | User and system balances scoped by chain and asset symbol |

Before a new deployment scans deposits, set `chain_scan_height` deliberately. A
fresh environment should usually start near the latest safe block. Move the
checkpoint backward only for a known replay window; scanning from genesis can be
slow and can exhaust public RPC quotas.

## Quick Start

Prerequisites:

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker for local BTC/LTC/DOGE/BCH/XMR regtest flows
- Node.js 18+ for EVM fork tests

Create and initialize a local database:

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

Build the backend:

```bash
mvn clean install -DskipTests
```

Start the three Java services from the repository root:

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

Common runtime secrets:

```bash
export SW_DB_PASSWORD='<PostgreSQL password>'
export SW_SIG1_MASTER_KEY='<BIP32 tprv for signer 1>'
export SW_SIG2_MASTER_KEY='<BIP32 tprv for signer 2>'
export SW_ED25519_SEED='<32-byte Ed25519 seed in hex or base64>'
export SW_WALLET_ADMIN_USERNAME='<wallet admin username>'
export SW_WALLET_ADMIN_PASSWORD='<wallet admin password>'
```

For production, keep the third BIP32 private root offline. wallet-server only
needs the three public roots in `wallet_public_key`; signer services own the
online private roots.

## Main APIs

| API | Purpose |
|---|---|
| `POST /wallet/v1/auth/register` | Register a wallet-only user |
| `POST /wallet/v1/auth/login` | Login and receive a wallet session |
| `GET /wallet/v1/auth/me` | Read current wallet user |
| `POST /wallet/v1/auth/logout` | Logout |
| `GET /wallet/v1/app/assets` | List wallet-visible assets and supported chains |
| `GET /wallet/v1/app/portfolio` | Read total assets and per-asset balances |
| `GET /wallet/v1/app/orders` | Read user deposit/withdraw/transfer records |
| `GET /wallet/v1/app/deposit-address` | Get an existing deposit address |
| `POST /wallet/v1/app/deposit-address` | Generate the next indexed deposit address |
| `POST /wallet/v1/app/withdraw` | Submit a withdrawal request |
| `POST /wallet/v1/app/transfer` | Transfer inside the wallet system |
| `POST /wallet/v1/app/test-faucet/doge` | Non-production DOGE regtest faucet |
| `POST /wallet/v1/app/test-faucet/xmr` | Non-production XMR regtest faucet |
| `GET /wallet/v1/dashboard` | Dashboard overview, docs, config snapshots, balances and records |
| `GET /wallet/v1/admin/config` | Admin config table summary |
| `PATCH /wallet/v1/admin/config/{table}/{id}` | Update allowlisted config fields |

The TokDou frontend wallet page is the current application caller. Local
development defaults to `http://localhost:8002`; deployed builds use
`https://api.tokdou.com` unless overridden by `VITE_WALLET_API_BASE`.

## Operational Rules

- One enabled network per chain in `chain_profile`.
- Enabled chains must have at least one enabled `chain_rpc_node` for the active
  Spring profile environment.
- API keys and RPC credentials are stored in DB rows, not in YAML. Production
  deployments should inject database credentials and master keys through the
  host secret mechanism.
- Every enabled chain needs one default hot-wallet row in `chain_address`:
  native asset, `user_id=0`, `biz=0`, `address_index=0`,
  `wallet_role=DEPOSIT`.
- Enabled token rows and active non-native assets must use real contracts or
  asset IDs. Placeholder contracts are forced disabled by the init SQL guard.
- Non-production faucet endpoints should stay unavailable in production profiles.

## Testing

Show the available test matrix:

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

Run DB-only account-chain tests:

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

Run local UTXO regtest flows:

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

External live/devnet spending tests require funded test wallets, working RPC
nodes, and faucet availability:

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
| HyperEVM and HyperCore | [docs/en/hyperevm-hypercore.md](docs/en/hyperevm-hypercore.md) | [docs/zh/hyperevm-hypercore.md](docs/zh/hyperevm-hypercore.md) |
| Infrastructure | [docs/en/infra.md](docs/en/infra.md) | [docs/zh/infra.md](docs/zh/infra.md) |
