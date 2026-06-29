# TokDou Asset Hub Token

This template maps the wallet contract deployment flow to Polkadot Asset Hub
`pallet-assets`.

Deployment creates one fungible asset with:

1. a deterministic wallet-generated asset id,
2. runtime metadata name, symbol and decimals,
3. optional initial supply minted to the deployment address,
4. deployer-owned asset admin/freezer roles,
5. an issuer role that is either retained by the deployer or assigned to a
   zero/dead address when owner minting is disabled.

This is not a WASM smart contract upload. It is a standard Asset Hub asset
created through runtime extrinsics.
