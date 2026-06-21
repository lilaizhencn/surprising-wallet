# Surprising Wallet — Multi-Chain Enterprise Wallet System

[🇨🇳 中文版本](./README_CN.md)

A production-grade, multi-chain wallet backend supporting **BTC (UTXO/SegWit multisig)**, **EVM-compatible chains**, **TRON**, and **future-ready adapters** for Solana & TON. Built with Java 17, Spring Boot, PostgreSQL, and Redis.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Supported Chains & Tokens](#supported-chains--tokens)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [SQL Initialization](#sql-initialization)
- [Public/Private Key Configuration](#publicprivate-key-configuration)
- [Service Modules](#service-modules)
- [Core Business Flows](#core-business-flows)
  - [Address Creation](#1-address-creation)
  - [Block Scanning & Deposit Detection](#2-block-scanning--deposit-detection)
  - [Deposit Crediting](#3-deposit-crediting)
  - [Fund Collection](#4-fund-collection)
  - [Withdrawal](#5-withdrawal)
  - [Multi-Sig vs Single-Sig](#6-multi-sig-vs-single-sig)
- [Configuration Reference](#configuration-reference)
- [Testing](#testing)
- [Important Notes](#important-notes)
- [Project Structure](#project-structure)
- [License](#license)

---

## Architecture Overview

The system follows a layered architecture with clear separation of concerns:

```
┌──────────────────────────────────────────────────────────┐
│                    wallet-server                          │
│   (Job Orchestration: Scan / Transfer / Withdraw)        │
├──────────────────────────────────────────────────────────┤
│                    wallet-service                         │
│   (Domain Layer: Chain Adapters, Wallets, DAOs)          │
├──────────────┬──────────────┬──────────────┬─────────────┤
│  wallet-sig1 │  wallet-sig2 │ wallet-sig-api│   common   │
│ (1st Sign)   │ (2nd Sign)   │ (Sign API)   │ (Utils)    │
├──────────────┴──────────────┴──────────────┴─────────────┤
│                   currency-sdks                          │
│  bitcoin-sdk │ wallet-common │ wallet-client │ tron-sdk  │
├──────────────────────────────────────────────────────────┤
│              PostgreSQL + Redis                          │
└──────────────────────────────────────────────────────────┘
```

### Chain Adapter Pattern

All blockchain interactions go through a unified **`BlockchainAdapter`** interface, with a **`BlockchainAdapterRegistry`** providing runtime lookup:

| Adapter | Family | Model | Status |
|---|---|---|---|
| `BtcChainAdapter` | bitcoin | UTXO (P2WSH 2-of-3 multisig) | ✅ Production |
| `EvmChainAdapter` | evm | Account (ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche) | ✅ Production |
| `TronChainAdapter` | tron | Account (energy/bandwidth model) | ✅ Production |
| `SolanaChainAdapter` | solana | Account (compute-unit model) | 🔮 Future-ready |
| `TonChainAdapter` | ton | Account (forward-fee model) | 🔮 Future-ready |

**Key Design Principle**: Each chain family has its own adapter. EVM chains share ONE engine differentiated by chain profiles (RPC URL, chain ID, gas policy). BTC remains isolated as a UTXO engine. TRON is separate due to its energy/bandwidth resource model. Future-chain adapters (Solana, TON) **fail fast** with `UnsupportedOperationException` until real RPC runtimes are wired — this prevents silent missed deposits.

---

## Supported Chains & Tokens

### Testnet (Enabled by Default)

| Chain | Chain ID | Native Token | ERC20/Token Standard | Gas Policy |
|---|---|---|---|---|
| BTC Testnet | `testnet3` | BTC | — | segwit-vbytes |
| Ethereum Sepolia | `11155111` | ETH | ERC20 (USDT, USDC) | eip1559 |
| BNB Chain Testnet | `97` | BNB | BEP20 (USDT, USDC) | legacy-gas-price |
| Polygon Amoy | `80002` | MATIC | ERC20 (USDT, USDC) | eip1559 |
| Arbitrum Sepolia | `421614` | ETH | ERC20 (USDT, USDC) | eip1559-l2 |
| Optimism Sepolia | `11155420` | ETH | ERC20 (USDT, USDC) | eip1559-l2 |
| Base Sepolia | `84532` | ETH | ERC20 (USDT, USDC) | eip1559-l2 |
| Avalanche Fuji | `43113` | AVAX | ERC20 (USDT, USDC) | eip1559 |
| TRON Mainnet | `tron-mainnet` | TRX | TRC20 | energy-bandwidth |

### Mainnet (Disabled by Default — Enable in `application.yaml`)

| Chain | Chain ID | Confirmations |
|---|---|---|
| Bitcoin Mainnet | `mainnet` | 6 |
| Ethereum Mainnet | `1` | 24 |
| BNB Chain Mainnet | `56` | 20 |
| Polygon Mainnet | `137` | 128 |
| Arbitrum One | `42161` | 40 |
| Optimism Mainnet | `10` | 40 |
| Base Mainnet | `8453` | 40 |
| Avalanche C-Chain | `43114` | 20 |
| Solana Mainnet | `mainnet-beta` | 32 |

---

## Prerequisites

- **JDK 17+**
- **Maven 3.8+**
- **PostgreSQL 14+**
- **Redis 6+**
- (Optional) Node.js 18+ for `evm-fork/` testing

---

## Quick Start

### 1. Clone & Build

```bash
git clone <repository-url>
cd surprising-wallet
mvn clean install -DskipTests
```

The project has **14 Maven modules** across two parent reactors.

### 2. Initialize Database

Connect to PostgreSQL and run the initialization scripts:

```bash
# Create the wallet database and user
psql -U postgres -c "CREATE USER wallet WITH PASSWORD 'wallet123';"
psql -U postgres -c "CREATE DATABASE wallet OWNER wallet;"

# Run BTC-focused schema (legacy tables)
psql -U wallet -d wallet -f surprising-wallet-init-pgsql.sql

# Run multi-chain schema (chain_asset, token_config, deposit_record, etc.)
psql -U wallet -d wallet -f multi-chain-wallet-schema.sql
```

See [SQL Initialization](#sql-initialization) for schema details.

### 3. Configure Application

Edit `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`:

- Set your RPC URLs under `atomex.chains.*`
- Configure public keys under `atomex.wallet.pubKey1/2/3`
- Adjust enabled chains and their `enabled` flags

Edit `backendservices/wallet-sig1/src/main/resources/application.yaml`:

- Set `atomex.wallet.masterKey` (first signer's extended private key)

Edit `backendservices/wallet-sig2/src/main/resources/application.yaml`:

- Set `atomex.wallet.masterKey` (second signer's extended private key)

### 4. Start Services (in order)

```bash
# 1. Start wallet-server (main orchestration, port 8002)
cd backendservices/wallet-parent/wallet-server
mvn spring-boot:run

# 2. Start wallet-sig1 (first signature service, port 8004)
cd backendservices/wallet-sig1
mvn spring-boot:run

# 3. Start wallet-sig2 (second signature service, port 8081)
cd backendservices/wallet-sig2
mvn spring-boot:run
```

### 5. Verify Health

```bash
curl http://localhost:8002/actuator/health
# Expected: {"status":"UP"}
```

---

## SQL Initialization

The project provides two SQL files. Apply **both** to have a complete schema.

### `surprising-wallet-init-pgsql.sql` — BTC Legacy Schema

Creates the BTC-specific tables used by the UTXO engine:

| Table | Purpose |
|---|---|
| `best_block_height` | Tracks scan progress per currency |
| `currency_balance` | Hot wallet aggregate balance per currency |
| `btc_address` | User deposit addresses (P2WSH derived from HD public keys) |
| `btc_utxo_transaction` | UTXO set tracking (one row per output) |
| `btc_withdraw_record` | Withdrawal request records |
| `btc_withdraw_transaction` | Signed transaction payloads |
| `user_asset` | User balance ledger (balance + frozen columns, optimistic locking via `version`) |
| `system_param` | Runtime parameters (network, script type, confirmations, fee rate) |
| `wallet_multisig_config` | 2-of-3 multisig public key configuration |

**Note**: This script **drops all existing tables** and recreates them. Only run on fresh databases.

### `multi-chain-wallet-schema.sql` — Multi-Chain Extensions

Uses `CREATE TABLE IF NOT EXISTS` — safe for incremental application:

| Table | Purpose |
|---|---|
| `chain_profile` | New-chain runtime id, BIP44 coin type, network, confirmations, fee/dust, RPC/explorer metadata |
| `chain_asset` | Chain-native asset definitions |
| `token_registry` | Token metadata (legacy, read as fallback) |
| `token_config` | **Primary** token configuration (enabled, min deposit/withdraw, collect settings) |
| `chain_scan_height` | Per-chain scanner progress tracking |
| `hot_wallet_address` | Hot wallet addresses per chain/asset |
| `deposit_record` | Normalized deposit events across all chains |
| `utxo_record` | Chain-scoped Bitcoin-like UTXO state (`AVAILABLE`/`LOCKED`/`SPENT`) |
| `withdrawal_order` | Multi-chain withdrawal orders |
| `evm_nonce` | Per-chain/address nonce management for EVM |
| `evm_transaction` / `evm_tx` | EVM transaction records |
| `evm_token_transfer` | ERC20/BEP20 transfer events |
| `tron_transaction` / `tron_tx` | TRON transaction records |
| `tron_token_transfer` | TRC20 transfer events |
| `sol_transaction` | Solana transaction records (future) |
| `ton_transaction` | TON transaction records (future) |
| `ledger_balance` | On-chain balance reconciliation |

### Token Configuration Example

After running the SQL, insert token configurations:

```sql
-- Enable USDT on Ethereum Sepolia
INSERT INTO token_config (chain, symbol, standard, contract_address, decimals, enabled, min_deposit, min_withdraw, collect_enabled)
VALUES ('ethereum-sepolia', 'USDT', 'ERC20', '0xYOUR_USDT_CONTRACT', 6, true, 1.0, 10.0, true);

-- Enable USDC on BNB Chain Testnet
INSERT INTO token_config (chain, symbol, standard, contract_address, decimals, enabled, min_deposit, min_withdraw, collect_enabled)
VALUES ('bnb-chain-testnet', 'USDC', 'BEP20', '0xYOUR_USDC_CONTRACT', 18, true, 1.0, 10.0, true);
```

The `JdbcTokenRegistry` reads `token_config` first, falling back to `token_registry` for backward compatibility.

For new chains, `chain_profile`, `chain_asset`, and `token_config` are the source of truth.
`CurrencyEnum` and `CurrencyIds` are legacy compatibility layers only. Runtime currency ids
must not be confused with BIP44 coin types.

---

## Public/Private Key Configuration

The wallet uses **HD (Hierarchical Deterministic)** key derivation based on BIP44.

### BTC Multi-Sig (2-of-3 P2WSH)

**wallet-server** holds only **extended public keys** (`tpub...`) for address derivation:

```yaml
atomex:
  wallet:
    pubKey1: tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB
    pubKey2: tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT
    pubKey3: tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4
```

**wallet-sig1** holds the first signer's **extended private key** (`tprv...`):

```yaml
atomex:
  wallet:
    masterKey: ${ATOMEX_SIG1_MASTER_KEY:}
```

**wallet-sig2** holds the second signer's **extended private key** (`tprv...`):

```yaml
atomex:
  wallet:
    masterKey: ${ATOMEX_SIG2_MASTER_KEY:}
```

### EVM Single-Sig

For EVM chains, address derivation uses the same HD root but produces secp256k1 Ethereum addresses. Signing is **single-key** — sig2 signs directly and pushes back through Redis.

### Derivation Path

```
m / 44' / <currency_index> / <biz> / <user_id> / <address_index>
```

| Parameter | Description |
|---|---|
| `currency_index` | BIP44 coin type (BTC=0, ETH=60, TRX=195) |
| `biz` | Business line (e.g., 0=hot wallet, 1=spot, 2=c2c) |
| `user_id` | User identifier |
| `address_index` | Sequential index per (biz, user_id) |

### Security Notes

- **Never** place private keys on wallet-server — it only needs public keys for address generation.
- Private keys live only on wallet-sig1 and wallet-sig2 (isolated signing services).
- For BTC 2-of-3 multisig, any 2 signers can authorize a transaction.
- The third key (`pubKey3`) is a backup/recovery key — its private key should be stored in cold storage.

---

## Service Modules

### wallet-server (Port 8002)

The orchestration layer. Runs scheduled jobs:

| Job | Description |
|---|---|
| `ScanBtcBlockJob` | Scans BTC blocks for deposits to system addresses |
| `ScanEthBlockJob` | Scans EVM blocks for native & ERC20 deposits |
| `ScanTronBlockJob` | Scans TRON blocks for TRX & TRC20 deposits |
| `BtcCollectionJob` | Consolidates UTXOs from user addresses to hot wallet |
| `EthTransferJob` / `Erc20TransferJob` | Collects EVM native/ERC20 balances to hot wallet |
| `TronTransferJob` | Collects TRX/TRC20 balances to hot wallet |
| `BatchBtcWithdrawJob` | Processes pending BTC withdrawal requests |
| `GetWithdrawRecordJob` | Monitors withdrawal confirmation status |
| `RetryFailedWithdraw` | Retries stuck withdrawals |
| `RbfBumpJob` | Bumps BTC transaction fees via RBF when needed |
| `SendRawTxJob` | Broadcasts signed transactions to the network |
| `FeeRateUpdater` | Refreshes BTC fee rate estimates |

### wallet-sig1 (Port 8004)

First signing service. Listens on a Redis queue (`WALLET_WITHDRAW_SIG_FIRST_KEY`) for unsigned transactions, produces the first witness/signature, then forwards to the second-sign queue.

### wallet-sig2 (Port 8081)

Second signing service. Picks up from Redis queue (`WALLET_WITHDRAW_SIG_SECOND_KEY`), completes the second signature (validates witness for BTC multisig), and pushes the fully-signed raw transaction back to wallet-server for broadcasting.

### wallet-service (Library)

Domain layer containing:
- **Chain adapters**: `BlockchainAdapter` implementations for each chain family
- **Wallet implementations**: `BtcWallet`, `EthWallet`, `Erc20Wallet`, `TronWallet`
- **DAOs & Services**: MyBatis repositories, transaction management, address management
- **Token registry**: `JdbcTokenRegistry` (primary), `InMemoryTokenRegistry` (fallback)

### currency-sdks (Libraries)

| Module | Purpose |
|---|---|
| `bitcoin-sdk` | BTC algorithm layer — `WitnessTransactionBuilder`, `P2wshFeeCalculator`, `TransactionBroadcastValidator`, UTXO selection |
| `wallet-common` | Shared domain models — `ChainType`, `ChainProfile`, `TransferQuote`, `DepositEvent`, entity POJOs |
| `wallet-client` | RPC command layer — `BtcCommand`, `EthLikeCommand`, `BlockChainBtcCommand` |
| `tron-sdk` | TRON protocol utilities |

### evm-fork (Testing)

Hardhat-based local EVM fork environment for integration testing. Deploys mock ERC20 tokens and validates full business flows (deposit → scan → credit → collect → withdraw) against forked testnet state.

---

## Core Business Flows

### 1. Address Creation

**BTC (P2WSH 2-of-3 Multisig)**:

1. Derive child public keys from the 3 extended public keys using the BIP44 path.
2. Construct a P2WSH redeem script: `OP_2 <pubkey1> <pubkey2> <pubkey3> OP_3 OP_CHECKMULTISIG`.
3. Compute the SegWit witness program (SHA256 of redeem script → RIPEMD160).
4. Encode as Bech32 address (P2WSH, starts with `tb1` on testnet).
5. Store in `btc_address` with derivation path, public keys, redeem script, and witness script.

**EVM Chains**:

1. Derive the child public key from the HD root at the BIP44 path.
2. Extract the Ethereum address: `keccak256(public_key)[12:32]` → `0x` prefix.
3. Store in the address shard table.

**TRON**:

1. Derive from the HD root at BIP44 path with TRON's coin type (195).
2. Encode as Base58 TRON address (starts with `T`).

### 2. Block Scanning & Deposit Detection

The scanning pipeline runs on wallet-server as scheduled jobs:

```
┌──────────┐    ┌────────────────┐    ┌──────────────┐    ┌─────────────┐
│ Get Best │───▶│ Calculate Scan │───▶│ Fetch Block   │───▶│ Extract      │
│ Height   │    │ Range          │    │ (by height)   │    │ Related TXs  │
└──────────┘    └────────────────┘    └──────────────┘    └─────────────┘
                                                                │
                                                                ▼
┌──────────┐    ┌────────────────┐    ┌──────────────┐    ┌─────────────┐
│ Update   │◀───│ Credit Deposit │◀───│ Save to DB    │◀───│ Match        │
│ Height   │    │ (if confirmed) │    │ (idempotent)  │    │ Addresses    │
└──────────┘    └────────────────┘    └──────────────┘    └─────────────┘
```

**BTC Deposit Detection**:
- Fetches blocks by height via BTC JSON-RPC.
- For each transaction, checks if any output address matches a system address in `btc_address`.
- Stores each matched output as a `UtxoTransaction` (idempotent insert via `UNIQUE(tx_id, seq)`).
- UTXOs are tracked individually; spent UTXOs are marked via `spent_tx_id`.

**EVM Deposit Detection**:
- Fetches blocks via `eth_getBlockByNumber` with full transaction objects.
- For native transfers: checks if `to` address matches any system address.
- For ERC20 transfers: decodes `Transfer(address,address,uint256)` event logs using the token's contract address from `token_config`/`token_registry`.
- Stores as `deposit_record` with idempotent `UNIQUE(chain, tx_hash, log_index)`.

**TRON Deposit Detection**:
- Similar to EVM but uses TRON's gRPC/HTTP API.
- Separates TRX native transfers from TRC20 token transfers.

**Idempotency**: All deposit inserts use `ON DUPLICATE KEY` / unique constraints — safe to rescan the same block.

### 3. Deposit Crediting

Once a deposit reaches the required **confirmation threshold**:

1. The deposit record's `status` transitions from `DETECTED` → `CREDITED`.
2. The user's `user_asset` balance is incremented (or `ledger_balance` for multi-chain).
3. For BTC: the UTXO-specific `credited` flag is set to `true`.
4. The credit operation is idempotent — duplicate scans do not double-credit.

**Confirmation Requirements** (configurable per chain in `application.yaml`):

| Chain | Testnet Confirmations | Mainnet Confirmations |
|---|---|---|
| BTC | 1 | 6 |
| Ethereum | 12 | 24 |
| BNB Chain | 20 | 20 |
| Polygon | 64 | 128 |
| Arbitrum/Optimism/Base | 40 | 40 |
| Avalanche | 20 | 20 |
| TRON | 20 | 20 |

### 4. Fund Collection

User deposits are periodically collected (swept) to a centralized hot wallet:

**BTC Collection**:
1. Query unspent UTXOs with sufficient confirmations.
2. Select UTXOs with a dust-aware optimizer.
3. Build a SegWit transaction: inputs = selected UTXOs, output = hot wallet address.
4. Calculate fee (SegWit vbyte-based) and subtract from collection amount.
5. Push to Redis signing queue for multi-sig signing.
6. After both signatures, broadcast.

**EVM Collection**:
1. Query account balances of user addresses on-chain.
2. If balance > `RESERVED` (gas buffer) + gas cost, build transfer.
3. For native: direct ETH transfer (21,000 gas).
4. For ERC20: call `transfer()` on token contract.
5. Nonce is managed via `EvmNonceManager` with DB-reserved nonces to prevent conflicts.

**Collection Safety**:
- A `RESERVED` amount stays on user addresses for gas fees.
- Internal transfers (hot wallet → hot wallet) are detected and skipped.
- Collection runs are rate-limited to prevent nonce congestion.

### 5. Withdrawal

```
User Request         Balance Freeze       Build TX             Signing Queue
    │                     │                   │                      │
    ▼                     ▼                   ▼                      ▼
┌────────┐    ┌──────────────────┐    ┌──────────────┐    ┌──────────────────┐
│ Create │───▶│ Freeze user      │───▶│ Select UTXOs  │───▶│ Push to Redis    │
│ Order  │    │ balance          │    │ / Build TX    │    │ (SIG_FIRST)      │
└────────┘    └──────────────────┘    └──────────────┘    └──────────────────┘
                                                                  │
                                          ┌───────────────────────┘
                                          ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────────┐
│ Update Order │◀───│ Broadcast    │◀───│ wallet-sig1      │
│ Status       │    │ Raw TX       │    │ → wallet-sig2    │
└──────────────┘    └──────────────┘    └──────────────────┘
```

**Step-by-step**:

1. **Order Creation**: User requests withdrawal. System validates balance, freezes the amount in `user_asset.frozen`.
2. **Transaction Building**:
   - **BTC**: Select UTXOs from hot wallet, construct P2WSH transaction with change output.
   - **EVM**: Reserve nonce via `EvmNonceManager`, build raw transaction with gas estimation.
3. **Signing Pipeline** (via Redis queues):
   - wallet-sig1 picks up the unsigned transaction, produces the first signature/witness, pushes to second queue.
   - wallet-sig2 picks up, adds the second signature, validates completeness, pushes back with `rawTransaction` hex.
4. **Broadcast**: wallet-server's `SendRawTxJob` sends the signed transaction to the network.
5. **Confirmation Tracking**: `GetWithdrawRecordJob` polls transaction status. On confirmation:
   - Locked balance is settled (deducted from `frozen` and `balance`).
   - Order status transitions to `CONFIRMED`.

**Failure Recovery**:
- `RetryFailedWithdraw`: Rebroadcasts stuck transactions.
- `RbfBumpJob`: Increases BTC transaction fees via Replace-By-Fee.
- Nonce recovery: On EVM nonce mismatch, the system retries with corrected nonce.

### 6. Multi-Sig vs Single-Sig

| Aspect | BTC (Multi-Sig) | EVM / TRON (Single-Sig) |
|---|---|---|
| **Scheme** | 2-of-3 P2WSH (SegWit) | Single private key |
| **Address format** | Bech32 (`tb1...` testnet, `bc1...` mainnet) | Hex (`0x...`) / Base58 (`T...`) |
| **Signing flow** | sig1 → sig2 (two signatures required) | sig2 only (single signature) |
| **Key distribution** | 3 extended public keys (server) + 2 private keys (sig1, sig2) + 1 cold backup | HD root → single private key (sig2) |
| **Fee model** | vbyte-based (SegWit weight units) | Gas limit × gas price (chain-specific) |
| **Change address** | Generated per transaction | N/A (account model) |
| **Dust protection** | UTXO-level dust threshold | Gas cost vs transfer amount check |

---

## Configuration Reference

### Key `application.yaml` Properties

#### wallet-server

| Property | Description | Default |
|---|---|---|
| `atomex.wallet.network` | Network mode (`test` or `main`) | `test` |
| `atomex.wallet.scan.enabled-currencies` | Currencies to scan | `btc` |
| `atomex.wallet.scan.start-height` | Initial scan height (`0` = from genesis) | `0` |
| `atomex.wallet.collection.enabled-currencies` | Currencies to auto-collect | `btc` |
| `atomex.wallet.confirmations.deposit` | Confirms before crediting deposit | `1` |
| `atomex.wallet.confirmations.withdraw` | Confirms for withdrawal tracking | `6` |
| `atomex.wallet.fee.default-rate-sat-vb` | Default BTC fee rate (sat/vB) | `10` |
| `atomex.wallet.hot.user-id` | Hot wallet user ID | `0` |
| `atomex.wallet.hot.biz` | Hot wallet business line | `0` |
| `atomex.wallet.hot.address-index` | Hot wallet address index | `0` |
| `atomex.btc.server` | BTC RPC endpoint | `https://bitcoin-testnet-rpc.publicnode.com` |
| `atomex.eth.server` | ETH RPC endpoint | `https://ethereum-sepolia-rpc.publicnode.com` |
| `atomex.eth.chain-id` | ETH chain ID | `11155111` |
| `atomex.tron.server` | TRON gRPC endpoint | `grpc.trongrid.io:50051` |

#### Per-Chain Configuration (`atomex.chains.<chain-name>`)

| Property | Description |
|---|---|
| `enabled` | Enable/disable this chain |
| `family` | Chain family: `btc`, `evm`, `tron`, `solana`, `ton` |
| `rpcUrl` | JSON-RPC / HTTP endpoint |
| `chainId` | Network identifier |
| `explorerUrl` | Block explorer base URL for transaction links |
| `confirmations` | Required confirmations for finality |
| `gasPolicy` | Gas estimation strategy |
| `scanBatchSize` | Blocks per scan batch |

**Gas Policies**:

| Policy | Used By | Description |
|---|---|---|
| `segwit-vbytes` | BTC | SegWit virtual byte fee estimation |
| `eip1559` | ETH, Polygon, Avalanche | Base fee + priority fee |
| `eip1559-l2` | Arbitrum, Optimism, Base | L2-aware EIP-1559 |
| `legacy-gas-price` | BNB Chain | Fixed gas price |
| `energy-bandwidth` | TRON | Energy + bandwidth resource model |
| `compute-unit` | Solana (future) | Compute unit budget |
| `ton-forward-fee` | TON (future) | TON forward fee model |

#### System Parameters (`system_param` table)

| Key | Description | Example Value |
|---|---|---|
| `wallet.network` | BTC network | `testnet3` |
| `wallet.script_type` | BTC multisig type | `P2WSH` |
| `wallet.deposit_confirmations` | Min confirms for deposit credit | `1` |
| `wallet.withdraw_confirmations` | Target confirms for withdrawal | `6` |
| `wallet.fee_rate_sat_vb` | Default fee rate | `10` |
| `wallet.scan_start_height` | Initial scan height | `0` |

---

## Testing

### Unit & Integration Tests

```bash
# Run all tests across 14 modules
mvn clean install -DskipTests=false

# Run only BTC SDK tests
cd currency-sdks/bitcoin-sdk
mvn test
```

**Test Coverage**:
- BTC SegWit multisig tests: 21 tests (address generation, witness shape, signing, fee calculation, UTXO optimization)
- Blockchain adapter registry tests
- EVM fork integration tests (Hardhat-based, requires Node.js)

### EVM Fork Regression Tests

Located in `evm-fork/`, these tests validate full business flows against forked testnet state:

```bash
cd evm-fork
npm install

# Run single-user flow tests
bash scripts/run-fork-regression.sh

# Run multi-user stability tests
RUN_MULTIUSER=true bash scripts/run-fork-regression.sh
```

The fork tests:
1. Fork live testnet state via Hardhat.
2. Deploy mock ERC20 tokens (USDT, USDC) with mint capability.
3. Create HD-derived wallet addresses.
4. Execute native & ERC20 transfers.
5. Validate deposit scanning, crediting, collection, and withdrawal.
6. Verify ledger consistency against on-chain balances.
7. Test multi-user isolation (users A/B/C/D with different flows).

---

## Important Notes

### Production Readiness

1. **Never commit private keys**. The `application.yaml` files in this repository contain **testnet keys only**. For production, use environment variables, a secrets manager (AWS Secrets Manager, HashiCorp Vault), or encrypted configuration.

2. **RPC reliability**: PublicNode endpoints are used for testnet. For mainnet, use dedicated RPC providers (Infura, Alchemy, QuickNode) or self-hosted full nodes.

3. **Polygon Amoy**: Known to have fork RPC issues due to missing historical state. Polygon fork integration tests are currently blocked. For production Polygon mainnet, use archive node access.

4. **Solana & TON**: Adapters exist but are **fail-fast** — they throw `UnsupportedOperationException` until real RPC scanner runtimes are wired. Do not enable them in production until the connectors are implemented.

5. **Database permissions**: The `wallet` database user needs `CREATE` privileges on schema `public` to run `multi-chain-wallet-schema.sql`. If using a restricted user, have a DBA run the migration.

6. **Redis**: Used as the signing pipeline message bus. Ensure Redis persistence (AOF/RDB) is configured to avoid losing in-flight signing jobs.

### BTC-Specific Notes

- **UTXO management**: The system tracks every UTXO individually. Long-running wallets will accumulate many UTXOs. The collection job consolidates them periodically.
- **Fee estimation**: Uses SegWit vbyte calculation. The formula accounts for witness discount (1 weight unit = 0.25 vbyte for witness data).
- **Change outputs**: Every BTC transaction generates a change output back to a hot wallet address.
- **RBF (Replace-By-Fee)**: The `RbfBumpJob` can increase fees on pending transactions.

### EVM-Specific Notes

- **Nonce management**: `EvmNonceManager` uses a database-backed reservation system. Nonces are reserved atomically to prevent conflicts between concurrent transfers.
- **Gas estimation**: Dynamic. Uses `eth_estimateGas` + `eth_gasPrice` (or `eth_maxPriorityFeePerGas` for EIP-1559 chains).
- **Token decimals**: Stored in `token_config.decimals`. Native ETH uses 18, USDT/USDC typically use 6.
- **L2 chains**: Arbitrum, Optimism, and Base use `eip1559-l2` gas policy, which accounts for L1 data fee components.

### TRON-Specific Notes

- **Energy & Bandwidth**: TRON uses a resource model different from gas. Transactions consume Energy (for smart contract calls) and Bandwidth (for data). Users can freeze TRX to obtain resources.
- **Dual API**: Supports both gRPC (legacy `atomex.tron.server`) and HTTP (PublicNode) endpoints.
- **Address format**: Base58check encoded, starting with `T`.

### Security Considerations

1. **Key isolation**: wallet-server (address generation) never sees private keys. Only wallet-sig1/sig2 hold signing keys.
2. **Multi-sig threshold**: BTC requires 2-of-3 signatures. A single compromised signing server cannot move funds.
3. **Cold backup key**: The third Bitcoin key (`pubKey3`) should have its private key in cold storage for disaster recovery.
4. **Optimistic locking**: `user_asset.version` prevents concurrent balance updates from corrupting user balances.
5. **Idempotent deposits**: All deposit inserts use unique constraints — rescanning the same block cannot double-credit.

---

## Project Structure

```
surprising-wallet/
├── pom.xml                          # Root Maven aggregator
├── surprising-wallet-init-pgsql.sql # BTC legacy schema
├── multi-chain-wallet-schema.sql    # Multi-chain extensions schema
│
├── currency-sdks/                   # Blockchain SDK libraries
│   ├── pom.xml
│   ├── bitcoin-sdk/                 # BTC algorithm library
│   │   └── src/main/java/.../
│   │       ├── WitnessTransactionBuilder.java
│   │       ├── P2wshFeeCalculator.java
│   │       └── TransactionBroadcastValidator.java
│   ├── wallet-common/               # Shared domain models
│   │   └── src/main/java/.../common/
│   │       ├── chain/               # ChainType, ChainProfile, TransferQuote
│   │       ├── pojo/                # Entity POJOs
│   │       └── dto/                 # DTOs
│   ├── wallet-client/               # RPC command layer
│   └── tron-sdk/                    # TRON utilities
│
├── backendservices/                 # Backend service modules
│   ├── pom.xml
│   ├── common/                      # Shared utilities (crypto, date, enums)
│   ├── wallet-sig-api/              # Signing API contracts
│   ├── wallet-sig1/                 # First signing service
│   │   └── src/main/java/.../sig/first/
│   │       ├── jobs/FirstSignJob.java
│   │       └── service/
│   │           └── BtcFirstSignService.java
│   ├── wallet-sig2/                 # Second signing service
│   │   └── src/main/java/.../sig/second/
│   │       └── impl/BtcSecondSignService.java
│   └── wallet-parent/
│       ├── wallet-service/          # Domain layer
│       │   └── src/main/java/.../wallet/service/
│       │       ├── chain/           # BlockchainAdapter implementations
│       │       │   ├── btc/BtcChainAdapter.java
│       │       │   ├── evm/         # EvmChainAdapter + sub-engines
│       │       │   ├── tron/TronChainAdapter.java
│       │       │   └── future/      # SolanaChainAdapter, TonChainAdapter
│       │       ├── wallet/          # Wallet implementations
│       │       │   ├── impl/BtcWallet.java
│       │       │   ├── impl/Erc20Wallet.java
│       │       │   └── impl/TronWallet.java
│       │       ├── service/         # Business services
│       │       │   ├── TransactionService.java
│       │       │   ├── UserAssetService.java
│       │       │   └── ...
│       │       └── dao/             # MyBatis repositories
│       └── wallet-server/           # Job orchestration
│           └── src/main/java/.../wallet/jobs/
│               ├── deposit/         # Block scanning jobs
│               ├── transfer/        # Collection jobs
│               └── withdraw/        # Withdrawal jobs
│
└── evm-fork/                        # Hardhat EVM fork test environment
    ├── contracts/MockERC20.sol
    ├── scripts/
    │   └── run-fork-regression.sh
    └── hardhat.config.js
```

---

## License

Proprietary. All rights reserved.

---

## Contributing

This is an internal project. Contact the maintainer for contribution guidelines.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
