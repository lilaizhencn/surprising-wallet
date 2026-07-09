# TokDou TON NFT Collection

This template maps the wallet ERC721-style deployment flow to the standard TON
NFT collection contract provided by ton4j.

Deployment creates a TEP-62 NFT collection with:

1. the deployment address as collection admin,
2. collection metadata URI,
3. base content URI for NFT item metadata,
4. deterministic TON collection address derived from StateInit.

This version deploys the collection contract only. NFT item minting is not
attached to the wallet token list automatically.
