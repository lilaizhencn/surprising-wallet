## Sui Contract Templates

`TokDouSuiCoin.move` and `TokDouSuiNft.move` are fixed source templates used
by wallet contract deployment. The backend renders validated user parameters
into a temporary Sui Move package, compiles it with the configured Sui CLI, and
publishes the compiled modules with the wallet's derived deployment address.

The Sui Coin template uses the Sui Coin Registry and keeps minting behind a
project-owned `MintAuthority` object so `max_supply` is enforced by the module.
If owner mint is disabled, the treasury cap is transferred to the null address.

Build command used during template validation:

```bash
sui move build --dump-bytecode-as-base64 --path <generated-package-dir>
```

The runtime does not accept arbitrary user code. It only compiles these fixed
templates after substituting validated token/NFT metadata and owner addresses.
