# Surprising Wallet V2 Roadmap

[中文版本](../zh/wallet-v2-roadmap.md)

This document records the next-stage product and technical direction for Surprising Wallet. Robinhood Chain, USDG, tokenized stocks, tokenized ETFs, LI.FI, Socket, and related items are documented as roadmap targets. Final chain ids, RPC endpoints, contract addresses, price sources, risk controls, and compliance switches must be confirmed during implementation, audit, and release approval.

## Goal

V2 aims to turn the wallet into a unified asset platform covering crypto, stablecoins, RWA, tokenized stocks, tokenized ETFs, DeFi, and Earn use cases:

- Users see one Portfolio and one Exchange entry point.
- Asset prices come from one Oracle price center.
- The system chooses the route automatically. Same-chain swaps, bridges, and cross-chain swaps are not exposed as separate user journeys.
- Cross-chain execution uses mature aggregators instead of an in-house bridge protocol.
- Balances, ledger events, quotes, route states, and chain confirmations must be auditable, traceable, and recoverable.

## Oracle Price Center

The goal is to build a unified asset price center for all wallet assets.

### Coverage

| Asset type | Usage |
|---|---|
| Crypto | Real-time price, value conversion, Portfolio display |
| Stablecoins | USDG, USDC, USDT pricing and depeg detection |
| Robinhood Chain stock tokens | Valuation for AAPL, NVDA, MSFT, and similar tokenized stocks |
| Tokenized ETFs | Valuation for QQQ, SPY, and similar ETF assets |
| DeFi / Earn | Future NAV, position, and yield display |

### Implementation Notes

- Oracle should output a normalized `asset + chain + quoteCurrency` price view. Portfolio must not depend directly on a single external provider.
- Every price must carry source, timestamp, precision, expiration, and abnormal-state metadata.
- Stablecoins need depeg alerts instead of assuming a fixed 1 USD value.
- RWA and securities tokens need extension fields for market hours, trading halts, delayed prices, and corporate actions.
- Amount calculations must continue using integer base units or `BigDecimal`, never floating-point arithmetic.

## Robinhood Chain

The goal is to integrate Robinhood Chain for the RWA and tokenized-assets ecosystem.

### Planned Assets

| Asset | Role |
|---|---|
| ETH | Gas asset |
| USDG | USD stablecoin |
| AAPL, NVDA, MSFT, and similar assets | Tokenized stocks |
| QQQ, SPY, and similar assets | Tokenized ETFs |

### Wallet Responsibilities

- Configure Robinhood Chain and its assets in `chain_profile`, `chain_rpc_node`, `chain_asset`, and `token_config`.
- Reuse the existing EVM-style address, signing, scanning, withdrawal, collection, and ledger flows unless Robinhood Chain requires a dedicated adapter.
- Use Oracle for USDG, stock-token, and ETF-token valuation in Portfolio.
- Keep compliance, region, KYC, asset-availability, and feature switches available for securities-like assets.

## Unified Exchange

The product should expose only one `Exchange` entry point. `Bridge` is an internal execution path inside cross-chain swap and should not be a separate user-facing product entry.

### Routing Rules

| User input | System path |
|---|---|
| Same chain, different assets | Swap |
| Different chains, same asset | Bridge |
| Different chains, different assets | Cross-chain Swap |

The system selects the best route from source chain, source asset, target chain, target asset, and amount. The UI should show one quote, fee summary, estimated receive amount, risk disclosure, and status flow.

## Bridge and Cross-chain Swap

The wallet should not build its own bridge protocol. It should use mature aggregators.

### Priority

| Option | Role |
|---|---|
| LI.FI | Primary aggregator |
| Socket | Backup aggregator |

The aggregator handles:

- Best route selection
- Bridge execution
- DEX swap execution
- Gas handling
- Liquidity selection

The wallet handles:

- Quote retrieval
- Asset, chain, amount, slippage, fee, and minimum-receive validation
- User-signable transaction generation
- Transaction submission or user-guided broadcast
- Transaction status lookup
- Quote, route, user signature, chain transaction, state transition, and final receive records
- Auditable ledger events for every state transition

### State and Idempotency

Exchange orders should at least distinguish:

| State | Meaning |
|---|---|
| `QUOTED` | Quote returned, not signed or submitted |
| `SIGNED` | User signed the transaction |
| `SUBMITTED` | Transaction submitted to source chain or aggregator |
| `SOURCE_CONFIRMED` | Source-chain transaction confirmed |
| `BRIDGING` | Bridge or intermediate route is executing |
| `TARGET_CONFIRMED` | Target-chain receive or target swap confirmed |
| `COMPLETED` | Wallet accounting and state finalized |
| `FAILED` | Execution failed and requires failure handling |
| `REFUNDING` | Refund or compensation in progress |
| `REFUNDED` | Refund completed |

Every state transition must be idempotent with constraints such as `orderId + routeId + chainTxHash + step` to prevent duplicate credits from repeated callbacks, polling, retries, or service restarts.

## USDG Support

USDG is planned as a USD stablecoin and future standard stablecoin in the wallet.

Planned scenarios:

- Transfer
- Payment
- DeFi
- Earn
- Portfolio valuation and stablecoin grouping

Before launch, verify:

- Contract address and decimals
- On-chain deposit, withdrawal, collection, and internal transfer
- Stablecoin depeg handling
- Fee asset and gas-balance requirements
- Idempotent ledger events for large and high-frequency transfers

## QQQ Support

QQQ is planned as a tokenized ETF and should be managed alongside crypto assets.

Requirements:

- Get valuation through Oracle.
- Display together with crypto and stablecoins in Portfolio while preserving RWA / Securities classification.
- Reserve fields for market hours, delayed prices, regional restrictions, KYC, delisting, and corporate actions.
- Keep market data, balance, and transfer status separately auditable.

## Roadmap

### Phase 1

| Capability | Goal |
|---|---|
| Oracle integration | Build a unified price center for crypto, stablecoins, and future RWA assets |
| Robinhood Chain integration | Validate chain config, asset config, address/signing/scanning/withdrawal paths |
| LI.FI integration | Implement quote, route, signing, submission, status lookup, and failure-handling skeleton |
| Unified Exchange entry | Use one entry point for swap, bridge, and cross-chain swap |

### Phase 2

Support major EVM assets:

- ETH
- USDC
- USDT

Implement:

- Same-chain Swap
- Bridge
- Cross-chain Swap
- Unified user experience, state model, and ledger records

### Phase 3

Integrate Robinhood Chain ecosystem assets:

- USDG
- Tokenized Stocks
- Tokenized ETFs such as QQQ and SPY

Complete:

- Portfolio
- RWA classification
- DeFi / Earn capabilities
- Securities-asset extension fields and risk switches

## Release Checklist

| Check | Requirement |
|---|---|
| Price | Source, timestamp, expiration, precision, and abnormal states are auditable |
| Asset | `chain_profile`, `chain_asset`, and `token_config` are complete |
| Balance | Every balance change has a ledger event and supports reconciliation |
| Idempotency | Quotes, orders, chain transactions, callbacks, polling, and retries have idempotency keys |
| State | Pending, confirmed, failed, refund, and reorg boundaries are explicit |
| Risk | Slippage, minimum receive, fees, route changes, and aggregator failures have user prompts and backend records |
| Security | No user private keys are stored; signing secrets and production RPC keys are not committed |
| Rollback | Aggregator, chain, asset, and Exchange entry points can be disabled by configuration |
