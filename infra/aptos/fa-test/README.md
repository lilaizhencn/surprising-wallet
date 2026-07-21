# Aptos local FA test assets

This Move package creates local-only USDC and USDT fungible assets for repeatable
wallet integration tests. It is not production token code and must never be used
as a mainnet/testnet token configuration.

Both assets use the current Aptos Fungible Asset standard, have six decimals,
and can only be minted by the account that publishes the module. The package
intentionally contains no legacy `Coin` or MUSD implementation.

Compile with a local publisher address:

```sh
aptos move compile \
  --package-dir infra/aptos/fa-test \
  --named-addresses test_fa=<publisher-address>
```

Run the complete isolated wallet flow from the repository root:

```sh
./infra/aptos/run-fa-flow.sh
```

The runner starts a fresh localnet, deploys and mints both FAs, creates a
temporary PostgreSQL database, executes deposit/replay/withdrawal/collection
tests, reconciles controlled on-chain balances with the ledger, and removes all
temporary state on exit.
