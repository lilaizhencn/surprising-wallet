# Architecture

[中文版本](../zh/architecture.md)

The wallet is organized as a set of Spring Boot services and SDK modules. Runtime routing is based on database asset metadata, not enum or numeric currency-id decisions.

![Architecture diagram](../assets/architecture-diagram.svg)

## Runtime Model

The runtime asset source is:

| Table | Role |
|---|---|
| `chain_profile` | Chain key, family, enabled network, confirmation policy, scan/withdraw/collection/transfer switches, scan start height, BIP44 coin type |
| `chain_rpc_node` | RPC/fullnode/indexer/faucet nodes, environment tag, priority, authentication, and remarks for each chain |
| `wallet_system_config` | Global scan/withdraw/collection/transfer switches |
| `wallet_public_key` | Three BIP32 public keys required by wallet-server startup |
| `chain_asset` | Native assets and chain-scoped asset definitions |
| `token_config` | Token contract/configuration, decimals, collect/withdraw policy |
| `ledger_balance` | Chain-scoped user/system balance state |

The application should resolve assets by `chain + symbol` or `chain + contract`, then pass a runtime asset into scanner, withdraw, collection, and signing flows.

## Modules

| Module | Responsibility |
|---|---|
| `backendservices/wallet-parent/wallet-server` | Spring Boot entry point, jobs, orchestration, application configuration |
| `backendservices/wallet-parent/wallet-service` | Chain services, scanners, transaction builders, repositories |
| `backendservices/wallet-sig1` | First signing service for BTC-like 2-of-3 flows |
| `backendservices/wallet-sig2` | Second signing service and EVM/TRON signing path |
| `backendservices/wallet-sig-api` | Shared signing API contracts |
| `currency-sdks/bitcoin-sdk` | Bitcoin-like transaction, address, and script support |
| `currency-sdks/tron-sdk` | TRON SDK integration |
| `currency-sdks/wallet-common` | Shared chain/key/runtime utilities |
| `currency-sdks/wallet-client` | Client interfaces |

## Chain Families

| Family | Chains | Local test support | Live/testnet support |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Docker regtest nodes | External RPC by configuration |
| EVM | ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche, HyperEVM, Mantle, Linea, Scroll, Unichain | Hardhat fork | Sepolia, HyperEVM testnet, Mantle Sepolia, Linea Sepolia, Scroll Sepolia, Unichain Sepolia, and other configured testnets |
| TRON | TRON | DB tests | Nile live flow |
| Solana | SOL, SPL tokens | DB tests | Devnet live flow |
| TON | TON, Jetton | DB tests | Testnet connectivity and funded live flow |
| Aptos | APT, coin resources | DB tests | Testnet live flow |
| Sui | SUI, coin resources | DB tests | Testnet live flow |
| HyperCore | USDC, HYPE, HIP-1 tokens | DB/API tests | Hyperliquid testnet API |

HyperEVM uses the shared EVM path for HYPE and ERC20 tokens. HyperCore uses its
own account-layer adapter backed by the official Hyperliquid `/info` and
`/exchange` APIs, not EVM JSON-RPC. See
[HyperEVM and HyperCore Integration](hyperevm-hypercore.md).

## Signing Model

Bitcoin-like chains use three BIP32 roots:

```text
BIP32 root #1 -> pubKey1, online signer 1 private root
BIP32 root #2 -> pubKey2, online signer 2 private root
BIP32 root #3 -> pubKey3, offline recovery private root
```

SOL/TON/APTOS/SUI use one Ed25519 master seed:

```text
SW_ED25519_SEED -> SLIP-0010 Ed25519 derivation -> per-chain/user key
```

Do not reuse production BIP32 raw seeds as the Ed25519 seed. Keep production root materials separated.

## Main Flow Boundaries

Scanner:

- Reads chain state.
- Finds registered addresses in `chain_address`.
- Writes normalized deposit events to `deposit_record`.
- Credits `ledger_balance` idempotently.

Withdraw:

- Resolves asset configuration from DB.
- Locks ledger balance.
- Builds/signs/broadcasts a transaction.
- Confirms and releases/finalizes ledger state.

Collection:

- Scans spendable user balances.
- Builds transfer to the fixed default hot wallet; this is the native-asset `chain_address` row with `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT`.
- Confirms and updates ledger state idempotently.

## Startup Configuration Validation

wallet-server validates `chain_profile`, `chain_rpc_node`, `wallet_public_key`, the default hot wallet, and `wallet_system_config` at startup. A chain can have only one enabled network. Production cannot enable test networks. Every enabled chain must have at least one RPC node for the current environment. The default hot wallet is re-derived and compared with `chain_address`; missing or mismatched rows fail startup. The validator logs each chain state and emits WARN logs for missing settings or disabled switches.

## Operational Directories

`scripts/`, `infra/`, and `evm-fork/` remain at the repository root because tests and scripts reference those paths directly. Documentation for them is under `docs/`.
