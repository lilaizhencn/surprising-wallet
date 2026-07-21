# Architecture

[中文版本](../zh/architecture.md)

The wallet is organized as a set of Spring Boot services and SDK modules. Runtime routing is based on database asset metadata, not enum or numeric currency-id decisions.

![Architecture diagram](../assets/architecture-diagram.svg)

## Custody control plane

The custody layer sits above the existing chain engine:

```text
Platform Console -> tenant lifecycle
Tenant Console/API -> tenant-scoped addresses, assets, deposits, withdrawals
                         |
                         v
              existing wallet ledger and chain engine
                         |
                         v
             scanners / signers / RPC providers
```

Tenant identity always comes from a Console session or API credential. The
public address API accepts `chainId`, a tenant-defined `subject`, and an optional
`addressVersion`; repeated requests for one tenant, chain, subject, and version
return the same address. Incrementing the version rotates the address, and all
EVM chains share one address for the same subject and version. Confirmed scanner credits are observed
in the same database transaction and mapped to a custody deposit, tenant
balance, and durable Webhook event. See
[Multi-tenant Custody](multi-tenant-custody.md).

## Runtime Model

The runtime asset source is:

| Table | Role |
|---|---|
| `chain_profile` | Chain key, family, enabled network, confirmation policy, scan/withdraw/collection/transfer switches, scan start height, BIP44 coin type |
| `chain_rpc_node` | RPC/fullnode/indexer/faucet nodes, environment tag, priority, authentication, and remarks for each chain |
| `wallet_system_config` | Global scan/withdraw/collection/transfer switches |
| `wallet_key_config` | Singleton keyset that atomically stores the sig1, sig2, and recovery BIP32 seeds plus one Ed25519 seed |
| `chain_asset` | Native assets and chain-scoped asset definitions |
| `token_config` | Token contract/configuration, decimals, collect/withdraw policy |
| `ledger_balance` | Chain-scoped user/system balance state |
| `custody_*` | Tenant, credential, address allocation, transfer projection, Webhook, idempotency, and audit control-plane state |

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

## Chain Families

| Family | Chains | Local test support | Live/testnet support |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Docker regtest nodes | External RPC by configuration |
| EVM | ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche, HyperEVM, Mantle, Linea, Scroll, Unichain | Hardhat fork | Sepolia, HyperEVM testnet, Mantle Sepolia, Linea Sepolia, Scroll Sepolia, Unichain Sepolia, and other configured testnets |
| TRON | TRON | DB tests | Nile live flow |
| Solana | SOL, SPL tokens | DB tests | Devnet live flow |
| TON | TON, Jetton | DB tests | Testnet connectivity and funded live flow |
| Aptos | APT, Coin<T>, Fungible Assets | DB tests | Testnet live flow |
| Sui | SUI, coin resources | DB tests | Testnet live flow |
| HyperCore | USDC, HYPE, HIP-1 tokens | DB/API tests | Hyperliquid testnet API |

HyperEVM uses the shared EVM path for HYPE and ERC20 tokens. HyperCore uses its
own account-layer adapter backed by the official Hyperliquid `/info` and
`/exchange` APIs, not EVM JSON-RPC. See
[HyperEVM and HyperCore Integration](hyperevm-hypercore.md).

## Signing Model

The singleton `wallet_key_config` row atomically stores four Base64-encoded 32-byte seeds. Bitcoin-like chains use three of them as BIP32 roots:

```text
BIP32 root #1 -> pubKey1, online signer 1 private root
BIP32 root #2 -> pubKey2, online signer 2 private root
BIP32 root #3 -> pubKey3, offline recovery private root
```

SOL/TON/APTOS/SUI use the fourth Ed25519 master seed:

```text
Ed25519 seed -> SLIP-0010 Ed25519 derivation -> per-chain/user key
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

wallet-server validates `chain_profile`, `chain_rpc_node`, `wallet_key_config`, the default hot wallet, and `wallet_system_config` at startup. When the keyset is not configured, wallet-server remains available for the platform administrator to complete initial configuration, while key-dependent runtime paths remain unavailable. Once configured, the default hot wallet is re-derived and compared with `chain_address`; missing or mismatched rows fail startup. Each chain may enable only one network at a time. Non-production environments may store devnet/testnet profiles together and switch the enabled profile, while production permits only production networks. Every enabled profile must have at least one RPC node for the current environment. The validator logs each chain state and emits WARN logs for missing settings or disabled switches.

## Operational Directories

`scripts/`, `infra/`, and `evm-fork/` remain at the repository root because tests and scripts reference those paths directly. Documentation for them is under `docs/`.
