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

## HyperCore Scope

`HYPERCORE` is integrated as a separate `hypercore` chain family. It is not an
EVM JSON-RPC chain and it must not be configured as another ERC20 chain under
`HYPEREVM`.

Current wallet scope:

- Address model: secp256k1/BIP44 account address, mirrored from the same owner
  account style used by EVM-compatible chains.
- Deposit scan: `spotClearinghouseState` snapshots from the official
  Hyperliquid `/info` API. The scanner records positive balance deltas as wallet
  deposit credits.
- Metadata sync: `spotMeta` from `/info` is stored in
  `hypercore_token_metadata` and `hypercore_spot_asset`.
- Withdraw and collection: signed Hyperliquid user actions sent to `/exchange`.
  Core USDC uses `usdSend`; HIP-1 tokens such as HYPE use `spotSend`.
- RPC config: enabled profiles require both `info` and `exchange` RPC node
  purposes. Test environments use `https://api.hyperliquid-testnet.xyz`.

Trading/order functions are intentionally excluded from the wallet path. The
wallet integration only handles custody-style address display, balance
observation, withdrawals and collection. HyperEVM <-> HyperCore transfers should
remain explicit bridge/internal-transfer features if they are added later; they
should not be treated as ERC20 deposit events.
