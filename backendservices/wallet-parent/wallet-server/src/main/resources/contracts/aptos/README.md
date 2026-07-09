# Aptos Contract Templates

TokDou deploys Aptos contracts as fixed Move packages compiled by the configured
Aptos CLI and published through `0x1::code::publish_package_txn`.

- `TokDouAptosCoin` creates an Aptos `Coin<T>` with configurable name, symbol,
  decimals, initial supply, max supply and optional owner mint.
- `TokDouAptosNft` creates a single-supply `Coin<T>` with decimals 0. This gives
  users a standard transferable Aptos asset path while avoiding arbitrary Move
  code. Aptos Digital Asset metadata is not attached in this version.

Both templates keep ownership at the deployment address. Users must fund the
separated `CONTRACT_DEPLOYER` Aptos address with APT before publishing.
