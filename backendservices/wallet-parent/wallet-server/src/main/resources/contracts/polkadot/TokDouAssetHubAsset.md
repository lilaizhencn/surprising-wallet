# TokDou Asset Hub Single Asset

This template maps the wallet ERC721-style flow to a Polkadot Asset Hub
single-supply asset.

Deployment creates one `pallet-assets` asset with decimals `0` and initial
supply `1`, minted to the deployment address. The asset id is deterministic
from the deployment address and validated parameters.

This version does not attach NFT metadata or use `pallet-uniques`; it provides
a lightweight single-supply asset path for wallet testing and simple issuance.
