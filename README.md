# Surprising Wallet

Multi-chain custodial wallet backend for exchange-style wallet flows. It provides
deterministic address generation, deposit scanning, user ledger balances,
withdrawal, internal transfer, collection, signer separation, and DB-driven chain
configuration.

[Chinese README](README_CN.md) | [Architecture](docs/en/architecture.md) | [Database](docs/en/database.md) | [Startup and testing](docs/en/startup-and-testing.md)

## Support

If you run into trouble or need technical support, contact the maintainer:

[![Email](https://img.shields.io/badge/Email-business%40tokdou.com-4285F4?style=flat-square&logo=google&logoColor=white)](mailto:business@tokdou.com)
[![Telegram](https://img.shields.io/badge/Telegram-%40SurprisingApp-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/SurprisingApp)

## Highlights

- Wallet app APIs for registration, login, portfolio, deposit address, withdrawal,
  internal transfer, and non-production faucet flows.
- EVM, TRON, NEAR, Solana, Aptos, Sui, Polkadot Asset Hub, and TON deployment APIs for wallet users,
  with fixed OpenZeppelin-style ERC20/ERC721, TRC20/TRC721, NEP-141/NEP-171,
  SPL Token/NFT mint, Aptos Coin/single-asset, Sui Move Coin/NFT, Asset Hub asset, and TON Jetton/NFT Collection templates, deployer-only derived addresses, gas balance checks,
  preview, broadcast, and deployment-order tracking.
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

Need support for another chain? Please open a
[GitHub issue](https://github.com/lilaizhencn/surprising-wallet/issues) with the
chain name, target network, native asset, token model, available RPC/indexer
options, and the wallet flows you need.

## Chain Matrix

| Family | Chains | Asset/token model | Runtime notes |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Native UTXO assets | 2-of-3 signing, local regtest support, external RPC by DB configuration |
| EVM | ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM, MANTLE, LINEA, SCROLL, UNICHAIN | Native gas asset plus ERC20, ERC20/ERC721 deployment templates | Shared EVM scanner, signer, withdrawal, collection, contract deployment, and Hardhat fork tests |
| TRON | TRON | TRX plus TRC20, TRC20/TRC721 deployment templates | Nile/testnet and mainnet profiles are DB-configured; contract deployment uses TRON fee_limit accounting |
| Solana | SOLANA | SOL plus SPL tokens, SPL Token/NFT mint deployment templates | Uses Ed25519 derivation, SPL token account handling, and standard SPL mint creation |
| TON | TON | TON plus Jetton, Jetton/NFT Collection deployment templates | Uses Ed25519 derivation, Jetton wallet resolution, deployer-gas scanning, and ton4j StateInit messages |
| Aptos | APTOS | APT plus Aptos coin/FA resources, Aptos Coin/single-asset deployment templates | Uses Ed25519 derivation, account-resource token lookup, and runtime Move package publish through the configured Aptos CLI |
| Sui | SUI | SUI plus Sui coin objects, Sui Coin/NFT deployment templates | Uses Sui RPC/gRPC-compatible runtime path, coin-object handling, and runtime Move compilation through the configured Sui CLI |
| XRP Ledger | XRP | XRP plus issued currencies such as USDC | Supports trustline-aware token handling |
| Cardano | ADA | ADA plus native-asset templates | Seeded as disabled templates until exact token/RPC settings are enabled |
| Polkadot | DOT | DOT plus Asset Hub assets, Asset Hub token/single-asset deployment templates | Westend/Asset Hub paths, runtime helper integration, native deployer-gas scanning, and pallet-assets creation |
| NEAR | NEAR | NEAR plus NEP-141 tokens, NEP-141/NEP-171 deployment templates | Uses implicit account derivation, storage-deposit handling, and precompiled Wasm deployment templates |
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

docs/                              English/Chinese docs, diagrams, DB init SQL
docs/db/surprising-wallet-init-pgsql.sql
evm-fork/                          Hardhat fork runtime for EVM regression tests
infra/                             local chain and service infrastructure
scripts/                           regtest and integration-test helpers
tools/contract-compiler/            Solidity template compiler workspace
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
| `contract_deployment_order` | EVM ERC20/ERC721, TRON TRC20/TRC721, NEAR NEP-141/NEP-171, Solana SPL Token/NFT mint, Aptos Coin/single-asset, Sui Coin/NFT, Polkadot Asset Hub, and TON Jetton/NFT Collection deployment preview, fee, tx, receipt, and status tracking |

Before a new deployment scans deposits, set `chain_scan_height` deliberately. A
fresh environment should usually start near the latest safe block. Move the
checkpoint backward only for a known replay window; scanning from genesis can be
slow and can exhaust public RPC quotas.

## Contract Deployment

Wallet users can deploy contracts or runtime assets on enabled EVM-family chains, TRON, NEAR, Solana, Aptos, Sui, Polkadot, and TON
without giving the backend arbitrary source code. The server exposes fixed,
compiled templates under `backendservices/wallet-parent/wallet-server/src/main/resources/contracts`:

The product flow is designed for a custodial wallet page: a logged-in user
chooses a chain and template, receives a dedicated deployment address, funds
that address with the native gas asset, previews the generated template and fee
quote, confirms the deployment, and tracks the resulting order. The backend
never accepts arbitrary user-supplied contract source code; it only accepts
validated parameters for audited project templates.

| Chain family | User-facing templates | Deployment result | Extra runtime requirement |
|---|---|---|---|
| EVM | ERC20, ERC721 | OpenZeppelin-style Solidity contracts | Enabled EVM JSON-RPC node for the selected chain |
| TRON | TRC20, TRC721 | TRON-compatible Solidity contracts | Enabled Nile/mainnet RPC, fee limit configuration |
| NEAR | NEP-141, NEP-171 | Wasm contract deployed to the user's implicit account | Enabled NEAR RPC; precompiled Wasm templates are bundled |
| Solana | SPL Token, SPL NFT mint | SPL mint and owner ATA | Enabled Solana RPC |
| Aptos | Coin, single-supply asset | Move package published from the deployer account | Aptos CLI available to wallet-server |
| Sui | Coin, NFT collection | Move package published from the deployer account | Sui CLI available to wallet-server |
| Polkadot Asset Hub | Fungible asset, single-supply asset | `pallet-assets` asset id and metadata | Local Polkadot runtime helper plus Asset Hub WebSocket RPC |
| TON | Jetton, NFT Collection | Deterministic StateInit contract | Enabled TON RPC/API key |

- `TokDouERC20`: OpenZeppelin-style ERC20 with owner, cap, pause, burn, permit,
  configurable decimals, initial supply, max supply, and optional owner mint.
- `TokDouERC721`: OpenZeppelin-style ERC721 with owner, enumerable tokens, URI
  storage, pause, burn, owner mint, base URI, and max supply.
- `TokDouTRC20`: OpenZeppelin-style TRC20 compiled for TRON-compatible
  Solidity, with owner, cap, pause, burn, permit, configurable decimals, initial
  supply, max supply, and optional owner mint.
- `TokDouTRC721`: OpenZeppelin-style TRC721 compiled for TRON-compatible
  Solidity, with owner, enumerable tokens, URI storage, pause, burn, owner mint,
  base URI, and max supply.
- `TokDouNep141`: near-sdk-js NEP-141 fungible token Wasm template with FT core
  methods, storage management, metadata, and owner initial supply.
- `TokDouNep171`: near-sdk-js NEP-171 NFT Wasm template with NFT core methods,
  metadata, enumeration, approval, and owner mint.
- `TokDouSplToken`: standard Solana SPL Token mint creation flow with mint
  rent, owner ATA creation, initial supply, optional owner mint authority, and
  revoked freeze authority.
- `TokDouSplNft`: single-supply Solana SPL mint flow with decimals 0, owner ATA
  delivery, revoked authorities, and no Metaplex metadata in this version.
- `TokDouAptosCoin`: Aptos Move Coin<T> package using `managed_coin`, owner
  initial supply, optional owner mint, and module-level max-supply checks.
- `TokDouAptosNft`: single-supply Aptos Move Coin<T> asset with decimals 0.
  Aptos Digital Asset metadata is not attached in this version.
- `TokDouSuiCoin`: Sui Move Coin template using Coin Registry, owner initial
  supply, optional owner mint through `MintAuthority`, and max-supply checks.
- `TokDouSuiNft`: Sui Move NFT template with a Collection object, owner mint,
  base URI, emitted mint events, and max-supply checks.
- `TokDouAssetHubToken`: Polkadot Asset Hub `pallet-assets` fungible asset
  creation flow with deterministic asset id, metadata, optional initial supply,
  and owner-controlled issuer role.
- `TokDouAssetHubAsset`: Polkadot Asset Hub single-supply asset flow with
  decimals 0, supply 1, and no NFT metadata in this version.
- `TokDouJetton`: TON TEP-74 Jetton minter deployment using ton4j, off-chain
  metadata URI, optional owner initial mint, and owner admin.
- `TokDouNftCollection`: TON TEP-62 NFT Collection deployment using ton4j,
  collection metadata URI, base item URI, and owner admin.

The deployment flow is intentionally separated from normal wallet deposits:

1. The user selects an EVM, TRON, NEAR, Solana, Aptos, Sui, Polkadot, or TON chain and requests a contract deployment
   address.
2. wallet-server derives that address with the existing deterministic key path,
   but stores it with `wallet_role=CONTRACT_DEPLOYER`.
3. The user funds that address with the chain's native gas asset.
4. Scanners can credit the address balance, while collection and normal
   withdrawal flows continue to use only ordinary `DEPOSIT` addresses.
5. The preview endpoint validates parameters, estimates EVM gas, applies a TRON
   `fee_limit`, reserves NEAR gas plus Wasm storage staking, or compiles the
   Sui Move template and reserves a fixed Sui publish gas budget. It checks both
   ledger and on-chain balance and returns constructor/init arguments and source
   preview. Solana reserves rent-exempt mint and owner associated-token-account
   balances plus estimated signature fees. Aptos compiles a fixed Move package
   and reserves max gas in APT. Polkadot checks Asset Hub asset-id availability
   and reserves the configured DOT runtime fee/deposit budget. TON checks
   deterministic contract-address availability and reserves the TON deployment
   balance used by the StateInit message.
6. The deploy endpoint signs and broadcasts the contract creation transaction,
   then stores tx, receipt, fee, and status in `contract_deployment_order`.

The app API sequence is:

| Step | Endpoint | Notes |
|---|---|---|
| Load choices | `GET /wallet/v1/app/contracts/templates` | Returns supported chains, template metadata, bytecode hash, features, and warnings |
| Create/fetch gas address | `POST /wallet/v1/app/contracts/deployer-address` | Body: `{ "chain": "BASE", "forceNew": false }`; returns address, QR code, derivation path, and role |
| Preview | `POST /wallet/v1/app/contracts/preview` | Validates fields, renders source/constructor args, checks ledger and chain balance, and returns `readyToDeploy` |
| Confirm deploy | `POST /wallet/v1/app/contracts/deploy` | Same body as preview plus `"confirmed": true`; freezes native gas balance before broadcast |
| Track orders | `GET /wallet/v1/app/contracts/orders?limit=20` | Refreshes pending orders and returns tx hash, contract address, fees, status, and errors |

Example preview/deploy payload:

```json
{
  "chain": "BASE",
  "templateType": "ERC20",
  "name": "TokDou Test USD",
  "symbol": "TUSDC",
  "decimals": 6,
  "initialSupply": "1000000",
  "maxSupply": "100000000",
  "mintable": true,
  "ownerAddress": "0x...",
  "confirmed": true
}
```

For ERC721-like templates, use `templateType: "ERC721"`, set `maxSupply`, and
provide `baseUri` when the target family supports metadata URIs. If
`ownerAddress` is omitted, the deployment address is used as the owner. Aptos,
Polkadot, and TON currently require the owner to be the deployment address so
that the published package, asset owner, or StateInit admin is controlled by the
same derived key.

Order statuses are intentionally simple:

| Status | Meaning |
|---|---|
| `PREVIEW` | Returned by preview only; no order row is persisted |
| `WAITING_FOR_FUNDS` | The deploy request could not freeze enough native gas balance |
| `SIGNING` | Order row was created and signing/broadcast is in progress |
| `SENT` | Transaction was broadcast; confirmation is still being refreshed |
| `CONFIRMED` | Deployment was confirmed and actual fee/contract address were recorded |
| `FAILED` | Validation, broadcast, or chain execution failed; unused locked balance is released when the failure is known before confirmation |

Contract deployment does not automatically add the deployed token to
`token_config` or to the wallet asset list. Token-list onboarding should remain
an explicit operational decision.

Operational boundaries:

- Deployment gas is paid by the user-funded `CONTRACT_DEPLOYER` address. Hot
  wallets do not top up gas for deployment.
- Deployment addresses start from a separate deterministic index range and are
  stored with `wallet_role=CONTRACT_DEPLOYER`, so normal deposits, withdrawals,
  and collection jobs can distinguish them from `DEPOSIT` addresses.
- Preview should be shown to the user before deploy. It includes generated
  source code, ABI where applicable, constructor/init arguments, gas quote,
  security notes, and warnings.
- The fixed templates include owner controls such as pause, mint, burn, cap, and
  max supply where the standard supports them. The UI should make those choices
  explicit because they affect token trust assumptions.
- On production databases, only enable chains whose RPC nodes, native gas
  balance scanning, confirmation rules, and CLI/helper dependencies are known to
  work in that environment.

Sui deployment requires the Sui CLI to be available to wallet-server. Configure
`sw.wallet.contract.sui.cli` when the executable is not named `sui`; the default
compile timeout is controlled by `sw.wallet.contract.sui.timeout-seconds`.

Solana deployment uses the existing wallet Solana RPC and `solanaj` SPL
instructions. It creates standard token mints rather than arbitrary Solana
programs, so no extra compiler toolchain is required.

Aptos deployment requires the Aptos CLI to be available to wallet-server.
Configure `sw.wallet.contract.aptos.cli` when the executable is not named
`aptos`; the compile timeout is controlled by
`sw.wallet.contract.aptos.timeout-seconds`.

Polkadot deployment uses the local `services/polkadot-runtime-service` helper
and Asset Hub WebSocket RPC nodes from `chain_rpc_node` with purpose
`asset_rpc`. It creates standard `pallet-assets` assets rather than uploading
Wasm smart contracts, so no compiler toolchain is required. The deployer
address must hold native DOT/WND on Asset Hub, and DOT native scanning includes
`wallet_role=CONTRACT_DEPLOYER` addresses so user-funded deployment gas can be
credited before preview/deploy.

TON deployment uses ton4j Jetton and NFT Collection builders to create
deterministic StateInit messages. The deployer address must hold TON for the
initial deployment balance, and TON native scanning includes
`wallet_role=CONTRACT_DEPLOYER` addresses. Jetton deposit scanning remains scoped
to normal `DEPOSIT` addresses.

## Quick Start

Prerequisites:

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker for local BTC/LTC/DOGE/BCH/XMR regtest flows
- Node.js 18+ for EVM fork tests and the Polkadot runtime helper
- Aptos CLI for Aptos Coin/single-asset Move package compilation and publish previews
- Sui CLI for Sui Coin/NFT Move template compilation and publish previews

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

### Key Material By Chain

The wallet intentionally keeps chain support on a small set of root materials:

| Material | Where it lives | Used for |
|---|---|---|
| `wallet_public_key` slots 1/2/3 | PostgreSQL | Public BIP32 roots required by wallet-server startup and deterministic address validation |
| `SW_SIG1_MASTER_KEY` | secret env/KMS | BIP32 private root for the first online signer |
| `SW_SIG2_MASTER_KEY` | secret env/KMS | BIP32 private root for the second signer and single-signer account-chain spending |
| `SW_ED25519_SEED` | secret env/KMS | Unified Ed25519 seed for Ed25519-based chains |
| XMR wallet-rpc wallet | monero-wallet-rpc host | Independent Monero wallet seed/cache/password managed outside BIP32/Ed25519 |

| Chains | Address derivation | Spending/signing source | Notes |
|---|---|---|---|
| BTC, LTC, DOGE, BCH | `wallet_public_key` slots 1/2/3 | `SW_SIG1_MASTER_KEY` + `SW_SIG2_MASTER_KEY` | 2-of-3 Bitcoin-like flow; slot 3 private root stays offline for recovery |
| ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM, MANTLE, LINEA, SCROLL, UNICHAIN | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | EVM secp256k1 addresses; shared BIP44 coin type 60 |
| TRON | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | secp256k1 key material converted to TRON address/signing format |
| XRP | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | XRP Ledger secp256k1 classic address, BIP44 coin type 144 |
| HYPERCORE | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | Hyperliquid API signing uses the same secp256k1 account root as EVM-style addresses |
| SOLANA, TON, APTOS, SUI, ADA, DOT, NEAR | `SW_ED25519_SEED` | `SW_ED25519_SEED` | One Ed25519 root with chain-specific SLIP-0010 paths; not stored in `wallet_public_key` |
| XMR | monero-wallet-rpc subaddresses | monero-wallet-rpc wallet | Back up the Monero wallet seed, cache files, and password together |

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
| `GET /wallet/v1/app/contracts/templates` | List supported EVM/TRON/NEAR/Solana/Aptos/Sui/Polkadot/TON deployment chains and ERC20/ERC721/TRC20/TRC721/NEP-141/NEP-171/SPL Token/SPL NFT/Aptos Coin/Aptos Asset/Sui Coin/Sui NFT/Asset Hub/Jetton/NFT Collection templates |
| `POST /wallet/v1/app/contracts/deployer-address` | Get or create a user contract-deployer address |
| `POST /wallet/v1/app/contracts/preview` | Validate parameters and estimate deployment gas |
| `POST /wallet/v1/app/contracts/deploy` | Sign and broadcast a contract creation transaction |
| `GET /wallet/v1/app/contracts/orders` | List the user's contract deployment orders |
| `GET /wallet/v1/dashboard` | Dashboard overview, docs, config snapshots, balances and records |
| `GET /wallet/v1/admin/config` | Admin config table summary |
| `PATCH /wallet/v1/admin/config/{table}/{id}` | Update allowlisted config fields |

The TokDou wallet page is only a demo/reference UI for this project. The wallet
backend does not require that frontend to run. In a real integration, teams are
expected to build their own Web, iOS, Android, or desktop application against
these APIs. The current `tokdou.com/wallet` page is intended to make the wallet
flows easier to understand visually, and it should be treated as a regtest/test
network demonstration rather than a mainnet-RPC production wallet. Local
development defaults to `http://localhost:8002`; deployed demo builds use
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
