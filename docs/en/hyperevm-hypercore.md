# HyperEVM and HyperCore Integration

[中文版本](../zh/hyperevm-hypercore.md)

## Current Scope

`HYPEREVM` is integrated through the shared EVM adapter:

- Native asset: `HYPE`
- Chain id: `998` on testnet, `999` on mainnet
- Address derivation: same secp256k1/BIP44 path used by EVM chains
- Deposit scan: native transfers and ERC20 `Transfer` logs
- Withdraw and collection: shared EVM signing, broadcast, confirmation and ledger flow

Circle Native USDC on HyperEVM testnet is configured as an ERC20 token:

```text
chain: HYPEREVM
symbol: USDC
standard: ERC20
network: testnet
contract: 0x2B3370eE501B4a559b57D449569354196457D8Ab
decimals: 6
```

The HyperEVM mainnet USDC contract is different:

```text
0xb88339CB7199b77E23DB6E890353E22632Ba630f
```

Because `token_config` is keyed by `chain + symbol`, each deployed database must
store the contract for its own active environment. Keep testnet contracts in
`dev`/`test2` databases. Replace them with mainnet contracts only in the prod
database before enabling prod.

## HyperCore Boundary

HyperCore should not be modeled as a normal EVM token chain. It is the
Hyperliquid core account/order-book layer, while HyperEVM is the EVM execution
layer. HyperCore support needs a dedicated adapter instead of another
`token_config` row.

Recommended phases:

1. Add a `hypercore` chain family and adapter that reads HyperCore account state
   from the official API.
2. Model HyperEVM <-> HyperCore transfers as explicit bridge/internal-transfer
   records, not ERC20 deposits.
3. Add signer support for Hyperliquid API actions only after the custody and
   withdrawal rules are finalized.
4. Keep trading/order functions out of the wallet path unless the product
   explicitly needs them.

This keeps the existing wallet flows stable: HyperEVM handles HYPE and ERC20
assets, while HyperCore can later be added as a separate account-layer feature.
