# System Code Flow

## 1. BTC deposit flow

```mermaid
flowchart TD
    A[ScanBtcBlockJob] --> B[AbstractScanBlockJob.execute]
    B --> C[BtcWallet.getBestHeight]
    B --> D[BtcWallet.findRelatedTxs]
    D --> E[AbstractBtcLikeWallet.getUtxo]
    E --> F[UtxoTransactionRepository.insertOnDuplicateKey]
    F --> G[TransactionService.saveTransaction]
    G --> H[TransactionService.creditDepositIfNeeded]
    H --> I[UserAssetService.addBalance]
```

1. `ScanBtcBlockJob` starts the BTC scan job.
2. `AbstractScanBlockJob.execute` resolves the best height, DB height, and scan range.
3. `BtcWallet.getBestHeight` asks the BTC RPC for the current chain height.
4. `BtcWallet.findRelatedTxs` parses each block transaction and extracts BTC outputs to system addresses.
5. `AbstractBtcLikeWallet.getUtxo` normalizes each output into a `UtxoTransaction`.
6. `UtxoTransactionRepository.insertOnDuplicateKey` keeps deposit rows idempotent.
7. `TransactionService.saveTransaction` pushes the deposit to the business queue.
8. `TransactionService.creditDepositIfNeeded` credits user balance once confirmations are sufficient.

## 2. BTC withdrawal flow

```mermaid
flowchart TD
    A[TransactionService.withdraw] --> B[AbstractBtcLikeWallet.withdraw]
    B --> C[UTXO selection / fee calc]
    C --> D[WithdrawTransaction row]
    D --> E[Redis signing queue]
    E --> F[wallet-sig1 first sign]
    F --> G[wallet-sig2 second sign]
    G --> H[sendRawTransaction]
    H --> I[Broadcast tx]
```

1. `TransactionService.withdraw` freezes user assets and creates a withdraw record.
2. `AbstractBtcLikeWallet.withdraw` checks hot-wallet balance and delegates to BTC transaction construction.
3. Fee calculation is SegWit vbytes-based and remains BTC-specific.
4. `WithdrawTransaction` is stored as the signing job payload.
5. Redis is used as the signing pipeline handoff.
6. `wallet-sig1` produces the first witness.
7. `wallet-sig2` completes the second signature and validates the witness.
8. `sendRawTransaction` broadcasts the signed BTC transaction.

## 3. EVM / ERC20 flow

```mermaid
flowchart TD
    A[EthWallet / Erc20Wallet] --> B[AbstractEthLikeWallet]
    B --> C[EvmChainAdapter]
    C --> D[EvmNonceManager]
    C --> E[EvmGasEstimator]
    C --> F[EvmTransactionBuilder]
    C --> G[EvmLogScanner]
    G --> H[JdbcTokenRegistry]
    H --> J[token_config / token_registry]
    H --> I[DepositEvent / AccountTransaction]
```

1. `EthWallet` keeps the existing ETH + ERC20 entrypoint.
2. `AbstractEthLikeWallet` still owns the current account-model deposit and withdraw logic.
3. `EvmChainAdapter` is the unified EVM engine facade.
4. `EvmNonceManager` allocates deterministic nonces per chain/address.
5. `EvmGasEstimator` produces chain-aware fee quotes.
6. `EvmTransactionBuilder` builds native and ERC20 transfer payloads.
7. `EvmLogScanner` converts logs into normalized deposit events.
8. `JdbcTokenRegistry` reads enabled token metadata from `token_config`, then falls back to legacy `token_registry`.
9. Generic `scanDeposits` is fail-fast until an RPC-backed scanner runtime is attached.

## 4. TRON flow

```mermaid
flowchart TD
    A[TronWallet] --> B[AbstractEthLikeWallet]
    A --> C[TronChainAdapter]
    C --> D[TronEnergyEstimator]
    C --> E[TRC20 / TRX quote]
    C --> F[Fail-fast scanner boundary]
```

1. `TronWallet` keeps the current TRX transfer and scan entrypoint.
2. `TronChainAdapter` exposes the unified TRON family interface.
3. `TronEnergyEstimator` models bandwidth and energy usage.
4. TRX and TRC20 quote paths are separated from EVM logic.
5. Generic TRON scanner calls fail fast until a real RPC-backed `TronScanner` is wired, so deposits cannot be silently missed.

## 5. Future chain flow

```mermaid
flowchart TD
    A[BlockchainAdapterRegistry] --> B[BtcChainAdapter]
    A --> C[EvmChainAdapter]
    A --> D[TronChainAdapter]
    A --> E[SolanaChainAdapter]
    A --> F[TonChainAdapter]
```

1. `BlockchainAdapterRegistry` is the single lookup point for chain engines.
2. BTC remains isolated.
3. EVM chains share one engine and multiple profiles.
4. TRON has its own resource model.
5. Solana and TON are represented as future-chain adapters with explicit unsupported transfer quotes and fail-fast scanners until their runtime connectors are enabled.
